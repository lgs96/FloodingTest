#include <iostream>
#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <atomic>
#include <chrono>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <string>
#include <vector>
#include <sstream>
#include <map>

/**
 * TCP Data Transmission Function
 * This function creates a TCP server that:
 * 1) Binds a TCP socket on the specified port
 * 2) Accepts connections
 * 3) Sends data in small bursts to maintain the specified rate
 */
void run_tcp_data_process(std::atomic<bool>& stop_flag, std::atomic<bool>& can_send_tcp, 
                         std::atomic<double>& tcp_rate_mbps, int port) {
    std::cout << "[TCP-PROC] Starting TCP server on port " << port << std::endl;
    
    // Create socket
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        std::cerr << "[TCP-PROC] Socket creation error" << std::endl;
        return;
    }
    
    // Set socket options
    int opt = 1;
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        std::cerr << "[TCP-PROC] setsockopt error" << std::endl;
        close(server_fd);
        return;
    }
    
    // Prepare address structure
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(port);
    
    // Bind socket
    if (bind(server_fd, (struct sockaddr*)&address, sizeof(address)) < 0) {
        std::cerr << "[TCP-PROC] Bind error" << std::endl;
        close(server_fd);
        return;
    }
    
    // Listen for connections
    if (listen(server_fd, 1) < 0) {
        std::cerr << "[TCP-PROC] Listen error" << std::endl;
        close(server_fd);
        return;
    }
    
    // Set socket timeout
    struct timeval timeout;
    timeout.tv_sec = 1;
    timeout.tv_usec = 0;
    if (setsockopt(server_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0) {
        std::cerr << "[TCP-PROC] Set receive timeout error" << std::endl;
    }
    
    // Prepare payload
    const int CHUNK_SIZE = 8192 * 8; // 8 KB chunk (same as Python version)
    char* payload_chunk = new char[CHUNK_SIZE]();  // Zero-initialized buffer
    
    while (!stop_flag.load()) {
        int client_socket = -1;
        
        // Accept connection
        socklen_t addrlen = sizeof(address);
        client_socket = accept(server_fd, (struct sockaddr*)&address, &addrlen);
        
        if (client_socket < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // Timeout, continue the loop
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            }
            std::cerr << "[TCP-PROC] Accept error: " << strerror(errno) << std::endl;
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }
        
        // Set client socket timeout
        if (setsockopt(client_socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0) {
            std::cerr << "[TCP-PROC] Set client timeout error" << std::endl;
        }
        
        std::cout << "[TCP-PROC] Accepted connection" << std::endl;
        
        // Send data until client disconnects or we stop
        while (!stop_flag.load()) {
            if (!can_send_tcp.load()) {
                // If not allowed to send, just sleep briefly
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                continue;
            }
            
            // Check the current rate
            double rate = tcp_rate_mbps.load();
            double target_Bps = (rate > 0) ? (rate * 1000000) / 8.0 : std::numeric_limits<double>::infinity();
            
            // Measure time before sending
            auto before_send = std::chrono::high_resolution_clock::now();
            
            // Send data
            ssize_t bytes_sent = send(client_socket, payload_chunk, CHUNK_SIZE, 0);
            if (bytes_sent < 0) {
                std::cerr << "[TCP-PROC] Send error: " << strerror(errno) << std::endl;
                break;
            } else if (bytes_sent == 0) {
                std::cout << "[TCP-PROC] Client disconnected" << std::endl;
                break;
            }
            
            // Calculate elapsed time
            auto after_send = std::chrono::high_resolution_clock::now();
            std::chrono::duration<double> elapsed = after_send - before_send;
            
            // Rate limiting
            if (target_Bps < std::numeric_limits<double>::infinity()) {
                double ideal_time = static_cast<double>(bytes_sent) / target_Bps;
                double leftover = ideal_time - elapsed.count();
                if (leftover > 0) {
                    std::this_thread::sleep_for(std::chrono::duration<double>(leftover));
                }
            }
        }
        
        // Close client socket before accepting a new connection
        if (client_socket >= 0) {
            close(client_socket);
        }
    }
    
    // Clean up
    delete[] payload_chunk;
    close(server_fd);
    std::cout << "[TCP-PROC] Exiting process" << std::endl;
}

#include <nlohmann/json.hpp>  // Include JSON library for PTP responses

/**
 * HTTP Control Server for managing the TCP transmission
 * Handles requests similar to the Python version:
 * - GET /ptp_sync            (PTP time synchronization)
 * - POST /ptp_delay_req      (PTP delay measurement)
 * - GET /start_tcp?rate=5    (start TCP with specified rate)
 * - GET /stop_tcp            (stop TCP transmission)
 */
class HttpControlServer {
private:
    int control_port;
    std::atomic<bool>& stop_flag;
    std::atomic<bool>& can_send_tcp;
    std::atomic<double>& tcp_rate_mbps;
    int server_fd;
    bool running;
    std::thread server_thread;
    
    // Parse HTTP request
    std::map<std::string, std::string> parseRequest(const std::string& request) {
        std::map<std::string, std::string> result;
        std::istringstream stream(request);
        std::string line;
        
        // Parse first line to get method and path
        if (std::getline(stream, line)) {
            std::istringstream first_line(line);
            std::string method, path, version;
            first_line >> method >> path >> version;
            
            result["method"] = method;
            
            // Parse path and query string
            size_t query_pos = path.find('?');
            if (query_pos != std::string::npos) {
                result["path"] = path.substr(0, query_pos);
                std::string query = path.substr(query_pos + 1);
                
                // Parse query parameters
                std::istringstream query_stream(query);
                std::string param;
                while (std::getline(query_stream, param, '&')) {
                    size_t eq_pos = param.find('=');
                    if (eq_pos != std::string::npos) {
                        std::string key = param.substr(0, eq_pos);
                        std::string value = param.substr(eq_pos + 1);
                        result[key] = value;
                    }
                }
            } else {
                result["path"] = path;
            }
        }
        
        return result;
    }
    
    // Create HTTP response
    std::string createResponse(int status_code, const std::string& content_type, const std::string& body) {
        std::string status_message = (status_code == 200) ? "OK" : "Not Found";
        std::ostringstream response;
        
        response << "HTTP/1.1 " << status_code << " " << status_message << "\r\n";
        response << "Content-Type: " << content_type << "\r\n";
        response << "Content-Length: " << body.length() << "\r\n";
        response << "Connection: close\r\n";
        response << "\r\n";
        response << body;
        
        return response.str();
    }
    
    // Handle HTTP request
    void handleRequest(int client_socket) {
        char buffer[4096] = {0};
        ssize_t bytes_read = recv(client_socket, buffer, sizeof(buffer) - 1, 0);
        
        if (bytes_read <= 0) {
            return;
        }
        
        std::string request(buffer);
        auto params = parseRequest(request);
        std::string response;
        
        if (params["method"] == "GET") {
            if (params["path"] == "/ptp_sync") {
                // Implementation of the SYNC + FOLLOW_UP message pattern
                using namespace std::chrono;
                
                // Calculate server receive time in microseconds (t2)
                auto now = high_resolution_clock::now();
                auto duration = now.time_since_epoch();
                auto t2 = duration_cast<microseconds>(duration).count();
                
                // Create JSON response with server receive timestamp
                nlohmann::json json_response = {
                    {"t2", t2}
                };
                
                std::string json_str = json_response.dump();
                std::cout << "[CONTROL] PTP SYNC message received, responded with t2=" << t2 << "µs" << std::endl;
                response = createResponse(200, "application/json", json_str);
            }
            else if (params["path"] == "/start_tcp") {
                // Extract rate parameter
                double rate = 0.0;
                if (params.find("rate") != params.end()) {
                    try {
                        rate = std::stod(params["rate"]);
                    } catch (...) {
                        rate = 0.0;
                    }
                }
                
                // Update control variables
                tcp_rate_mbps.store(rate);
                can_send_tcp.store(true);
                
                std::cout << "[CONTROL] START_TCP with rate " << rate << " Mbps" << std::endl;
                response = createResponse(200, "text/plain", "OK: started TCP\n");
            }
            else if (params["path"] == "/stop_tcp") {
                // Update control variables
                can_send_tcp.store(false);
                tcp_rate_mbps.store(0.0);
                
                std::cout << "[CONTROL] STOP_TCP => done" << std::endl;
                response = createResponse(200, "text/plain", "OK: stopped TCP\n");
            }
            else {
                response = createResponse(404, "text/plain", "Unknown command\n");
            }
        } else if (params["method"] == "POST") {
            if (params["path"] == "/ptp_delay_req") {
                // Implementation of the DELAY_REQ + DELAY_RESP message pattern
                using namespace std::chrono;
                
                // Parse request body to extract client timestamps
                std::string body;
                size_t body_start = request.find("\r\n\r\n");
                if (body_start != std::string::npos) {
                    body = request.substr(body_start + 4);
                }
                
                try {
                    // Parse JSON request with client timestamps
                    auto json_request = nlohmann::json::parse(body);
                    int64_t t1 = json_request["t1"];
                    int64_t t3 = json_request["t3"];
                    
                    // Calculate server receive timestamp for DELAY_RESP (t4)
                    auto now = high_resolution_clock::now();
                    auto duration = now.time_since_epoch();
                    auto t4 = duration_cast<microseconds>(duration).count();
                    
                    // Create JSON response with server timestamp
                    nlohmann::json json_response = {
                        {"t4", t4}
                    };
                    
                    std::string json_str = json_response.dump();
                    std::cout << "[CONTROL] PTP DELAY_REQ processed: t1=" << t1 << "µs, t3=" << t3 
                              << "µs, t4=" << t4 << "µs" << std::endl;
                    response = createResponse(200, "application/json", json_str);
                } catch (const std::exception& e) {
                    std::cerr << "[CONTROL] Error parsing PTP request: " << e.what() << std::endl;
                    response = createResponse(400, "text/plain", "Invalid request format\n");
                }
            } else {
                response = createResponse(404, "text/plain", "Unknown command\n");
            }
        } else {
            response = createResponse(404, "text/plain", "Method not supported\n");
        }
        
        // Send response
        send(client_socket, response.c_str(), response.length(), 0);
    }
    
    // Server main loop
    void serverLoop() {
        while (!stop_flag.load() && running) {
            // Accept connection with timeout
            struct sockaddr_in client_addr;
            socklen_t client_len = sizeof(client_addr);
            
            fd_set read_fds;
            FD_ZERO(&read_fds);
            FD_SET(server_fd, &read_fds);
            
            struct timeval timeout;
            timeout.tv_sec = 1;
            timeout.tv_usec = 0;
            
            int activity = select(server_fd + 1, &read_fds, NULL, NULL, &timeout);
            
            if (activity < 0 && errno != EINTR) {
                std::cerr << "[CONTROL] Select error" << std::endl;
                continue;
            }
            
            if (activity == 0) {
                // Timeout, continue the loop
                continue;
            }
            
            if (!FD_ISSET(server_fd, &read_fds)) {
                continue;
            }
            
            int client_socket = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
            if (client_socket < 0) {
                std::cerr << "[CONTROL] Accept error: " << strerror(errno) << std::endl;
                continue;
            }
            
            // Handle request
            handleRequest(client_socket);
            
            // Close connection
            close(client_socket);
        }
    }
    
public:
    HttpControlServer(int port, std::atomic<bool>& stop, std::atomic<bool>& can_send, std::atomic<double>& rate)
        : control_port(port), stop_flag(stop), can_send_tcp(can_send), tcp_rate_mbps(rate), running(false) {
    }
    
    ~HttpControlServer() {
        stop();
    }
    
    bool start() {
        // Create socket
        server_fd = socket(AF_INET, SOCK_STREAM, 0);
        if (server_fd < 0) {
            std::cerr << "[CONTROL] Socket creation error" << std::endl;
            return false;
        }
        
        // Set socket options
        int opt = 1;
        if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
            std::cerr << "[CONTROL] setsockopt error" << std::endl;
            close(server_fd);
            return false;
        }
        
        // Prepare address structure
        struct sockaddr_in address;
        memset(&address, 0, sizeof(address));
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = INADDR_ANY;
        address.sin_port = htons(control_port);
        
        // Bind socket
        if (bind(server_fd, (struct sockaddr*)&address, sizeof(address)) < 0) {
            std::cerr << "[CONTROL] Bind error" << std::endl;
            close(server_fd);
            return false;
        }
        
        // Listen for connections
        if (listen(server_fd, 5) < 0) {
            std::cerr << "[CONTROL] Listen error" << std::endl;
            close(server_fd);
            return false;
        }
        
        running = true;
        server_thread = std::thread(&HttpControlServer::serverLoop, this);
        
        std::cout << "[CONTROL] HTTP server started on port " << control_port << std::endl;
        return true;
    }
    
    void stop() {
        if (running) {
            running = false;
            if (server_thread.joinable()) {
                server_thread.join();
            }
            close(server_fd);
            std::cout << "[CONTROL] HTTP server stopped" << std::endl;
        }
    }
};

/**
 * Complete working example with both TCP transmission and control server
 */
int main(int argc, char* argv[]) {
    // Default ports
    int control_port = 8889;
    int data_port = 8890;
    
    // Parse command line arguments
    if (argc >= 3) {
        control_port = std::atoi(argv[1]);
        data_port = std::atoi(argv[2]);
    }
    
    // Shared control variables
    std::atomic<bool> stop_flag(false);
    std::atomic<bool> can_send_tcp(false);
    std::atomic<double> tcp_rate_mbps(0.0);
    
    std::cout << "Starting server. CONTROL_PORT=" << control_port 
              << ", DATA_PORT=" << data_port << std::endl;
              
    // Make sure the nlohmann/json library is properly linked
    #ifndef NLOHMANN_JSON_VERSION_MAJOR
    #error "nlohmann/json library is required. Please install it before compiling."
    #endif
    
    // Start TCP data process in a thread
    std::thread tcp_thread(run_tcp_data_process, 
                         std::ref(stop_flag), 
                         std::ref(can_send_tcp), 
                         std::ref(tcp_rate_mbps), 
                         data_port);
    
    // Start HTTP control server
    HttpControlServer control_server(control_port, stop_flag, can_send_tcp, tcp_rate_mbps);
    if (!control_server.start()) {
        std::cerr << "Failed to start control server. Exiting." << std::endl;
        stop_flag.store(true);
        tcp_thread.join();
        return 1;
    }
    
    // Wait for Ctrl+C
    std::cout << "Server running. Press Ctrl+C to stop." << std::endl;
    
    try {
        // Wait until interrupted
        while (!stop_flag.load()) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    } catch (const std::exception& e) {
        std::cerr << "Exception: " << e.what() << std::endl;
    } catch (...) {
        std::cerr << "Unknown exception" << std::endl;
    }
    
    // Clean shutdown
    std::cout << "Shutting down..." << std::endl;
    control_server.stop();
    stop_flag.store(true);
    tcp_thread.join();
    
    std::cout << "Server fully shut down." << std::endl;
    return 0;
}