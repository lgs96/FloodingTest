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

#include <fstream>
#include <sstream>
#include <iomanip>
#include <filesystem>
#include <chrono>

#define DEBUG_LOG(msg) std::cout << "[DEBUG] " << msg << std::endl

// Global variables to track current log file (similar to Python implementation)
std::string current_log_filename;
std::string current_protocol;

/**
 * Creates a new CSV log file for the given protocol ('tcp' or 'udp'),
 * writes the CSV header, and tracks it globally.
 */
void start_new_log_file(const std::string& protocol) {
    DEBUG_LOG("Creating new log file: " + protocol);
    // Close any previous log file first
    if (!current_log_filename.empty()) {
        std::cout << "[" << (current_protocol.empty() ? "UNKNOWN" : current_protocol) 
                 << "] Closed previous log file 'logs/" << current_log_filename << "'" << std::endl;
    }
    
    // Get current timestamp for filename
    auto now = std::chrono::system_clock::now();
    auto now_time_t = std::chrono::system_clock::to_time_t(now);
    std::tm now_tm = *std::localtime(&now_time_t);
    
    std::ostringstream filename_stream;
    filename_stream << protocol << "_"
                   << std::put_time(&now_tm, "%Y_%m_%d_%H_%M_%S") << ".csv";
    
    current_log_filename = filename_stream.str();
    current_protocol = protocol;
    
    // Create logs directory if it doesn't exist
    std::filesystem::create_directories("logs");
    
    // Create and initialize log file with header
    std::string path = "logs/" + current_log_filename;
    std::ofstream file(path);
    if (file.is_open()) {
        file << "client_timestamp,client_epoch_ms,server_timestamp,server_epoch_ms,throughput" << std::endl;
        file.close();
        std::cout << "[" << protocol << "] Created log file 'logs/" << current_log_filename << "'" << std::endl;
    } else {
        std::cerr << "[ERROR] Failed to create log file: " << path << std::endl;
    }
}

/**
 * Appends CSV log data to the current log file.
 * If no log file is set, creates an 'unknown' file.
 */
void append_csv_logs(const std::string& csv_data) {
    // If no log file exists yet, create one
    if (current_log_filename.empty()) {
        auto now = std::chrono::system_clock::now();
        auto now_time_t = std::chrono::system_clock::to_time_t(now);
        std::tm now_tm = *std::localtime(&now_time_t);
        
        std::ostringstream filename_stream;
        filename_stream << "unknown_" << std::put_time(&now_tm, "%Y_%m_%d_%H_%M_%S") << ".csv";
        
        current_log_filename = filename_stream.str();
        current_protocol = "unknown";
        
        // Create logs directory if it doesn't exist
        std::filesystem::create_directories("logs");
        
        // Create and initialize log file with header
        std::string path = "logs/" + current_log_filename;
        std::ofstream file(path);
        if (file.is_open()) {
            file << "client_timestamp,client_epoch_ms,server_timestamp,server_epoch_ms,throughput" << std::endl;
            file.close();
        }
    }
    
    // Append to log file
    std::string path = "logs/" + current_log_filename;
    std::ofstream file(path, std::ios_base::app);
    if (file.is_open()) {
        file << csv_data << std::endl;
        file.close();
    } else {
        std::cerr << "[ERROR] Failed to append to log file: " << path << std::endl;
    }
}

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
        
        // Parse headers to get content length
        int content_length = 0;
        while (std::getline(stream, line) && !line.empty() && line != "\r") {
            // Trim carriage return if present
            if (!line.empty() && line.back() == '\r') {
                line.pop_back();
            }
            
            // Parse header line
            size_t colon_pos = line.find(':');
            if (colon_pos != std::string::npos) {
                std::string header_name = line.substr(0, colon_pos);
                std::string header_value = line.substr(colon_pos + 1);
                
                // Trim leading whitespace from header value
                header_value.erase(0, header_value.find_first_not_of(" \t"));
                
                // Convert header name to lowercase for case-insensitive comparison
                std::transform(header_name.begin(), header_name.end(), header_name.begin(), 
                               [](unsigned char c){ return std::tolower(c); });
                
                if (header_name == "content-length") {
                    try {
                        content_length = std::stoi(header_value);
                        result["content_length"] = header_value;
                    } catch (...) {
                        // Ignore parsing errors
                    }
                }
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
        char buffer[8192] = {0};  // Increased buffer size
        ssize_t bytes_read = recv(client_socket, buffer, sizeof(buffer) - 1, 0);
        
        if (bytes_read <= 0) {
            return;
        }
        
        std::string request(buffer, bytes_read);
        auto params = parseRequest(request);
        std::string response;

        // Add a buffer to store incomplete lines between requests
        std::string incomplete_line_buffer;
        
        // Debug output
        std::cout << "[DEBUG] Received request: " << params["method"] << " " << params["path"] << std::endl;
        
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
                
                // Create a new log file - ADD THIS LINE!
                start_new_log_file("tcp");
                
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
                
                // Debug the raw body to see what we're getting
                std::cout << "[DEBUG] POST body: " << (body.empty() ? "EMPTY" : body) << std::endl;
                
                try {
                    // Parse JSON request with client timestamps
                    auto json_request = nlohmann::json::parse(body);
                    
                    // Check if keys exist before accessing them (t1 and t2, as sent by Android client)
                    if (!json_request.contains("t1")) {
                        throw std::runtime_error("Missing required timestamp (t1)");
                    }
                    
                    // Get timestamps, ensure they're valid numbers
                    int64_t t1 = json_request["t1"].get<int64_t>();
                    
                    // t2 is included in Android client but not in the Python test client
                    int64_t t2 = 0;
                    if (json_request.contains("t2")) {
                        t2 = json_request["t2"].get<int64_t>();
                    }
                    
                    // Android client doesn't send t3, it's calculated on the server
                    // Calculate server receive timestamp for DELAY_RESP (t4)
                    auto now = high_resolution_clock::now();
                    auto duration = now.time_since_epoch();
                    auto t4 = duration_cast<microseconds>(duration).count();
                    
                    // Create JSON response with server timestamp
                    nlohmann::json json_response = {
                        {"t4", t4}
                    };
                    
                    std::string json_str = json_response.dump();
                    std::cout << "[CONTROL] PTP DELAY_REQ processed: t1=" << t1 << "µs, t2=" << t2 
                              << "µs, t4=" << t4 << "µs" << std::endl;
                    response = createResponse(200, "application/json", json_str);
                } catch (const std::exception& e) {
                    std::cerr << "[CONTROL] Error parsing PTP request: " << e.what() << std::endl;
                    response = createResponse(400, "text/plain", std::string("Invalid request format: ") + e.what() + "\n");
                } catch (const std::exception& e) {
                    std::cerr << "[CONTROL] Error parsing PTP request: " << e.what() << std::endl;
                    response = createResponse(400, "text/plain", "Invalid request format\n");
                }
            } 
            else if (params["method"] == "POST" && params["path"] == "/csv_logs") {
                // Extract the POST body content
                std::string body;
                size_t body_start = request.find("\r\n\r\n");
                if (body_start != std::string::npos) {
                    body = request.substr(body_start + 4);
                }
                
                // Get content length and read the full body if needed
                int content_length = 0;
                if (params.find("content_length") != params.end()) {
                    content_length = std::stoi(params["content_length"]);
                    
                    // If we need more data, keep reading
                    if (content_length > body.length()) {
                        std::vector<char> buffer(content_length - body.length() + 1, 0);
                        size_t remaining = content_length - body.length();
                        size_t total_read = 0;
                        
                        while (total_read < remaining) {
                            ssize_t bytes_read = recv(client_socket, buffer.data() + total_read, 
                                                    remaining - total_read, 0);
                            if (bytes_read <= 0) break;
                            total_read += bytes_read;
                        }
                        
                        if (total_read > 0) {
                            body.append(buffer.data(), total_read);
                        }
                    }
                }
                
                std::cout << "[DEBUG] Received CSV data of length: " << body.length() << std::endl;
                
                // Process the CSV data with proper line handling
                if (!body.empty()) {
                    std::vector<std::string> lines_to_append;
                    
                    // Get current time for reference (in milliseconds)
                    auto now = std::chrono::system_clock::now();
                    auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                        now.time_since_epoch()).count();
                    
                    // Prepend any incomplete line from previous request
                    if (!incomplete_line_buffer.empty()) {
                        body = incomplete_line_buffer + body;
                        incomplete_line_buffer.clear();
                        std::cout << "[DEBUG] Added incomplete line from previous request" << std::endl;
                    }
                    
                    std::istringstream body_stream(body);
                    std::string line;
                    
                    // Process each complete line, save incomplete line for next request
                    while (std::getline(body_stream, line)) {
                        // Check if this line was properly terminated with newline
                        // If we're at the end of the stream and didn't hit a newline, it's incomplete
                        if (body_stream.eof() && body.back() != '\n' && body.back() != '\r') {
                            incomplete_line_buffer = line;
                            std::cout << "[DEBUG] Saved incomplete line for next request: " 
                                    << line.substr(0, std::min(size_t(50), line.length())) 
                                    << (line.length() > 50 ? "..." : "") << std::endl;
                            continue;
                        }
                        
                        // Trim any trailing \r
                        if (!line.empty() && line.back() == '\r') {
                            line.pop_back();
                        }
                        
                        if (line.empty()) {
                            continue;
                        }
                        
                        // Parse the line to extract client_epoch_ms
                        std::istringstream line_stream(line);
                        std::string field;
                        int field_index = 0;
                        double client_epoch_ms = 0;
                        bool timestamp_parsed = false;
                        
                        while (std::getline(line_stream, field, ',')) {
                            if (field_index == 1) {  // client_epoch_ms is the second field (index 1)
                                try {
                                    client_epoch_ms = std::stod(field);
                                    timestamp_parsed = true;
                                } catch (const std::exception& e) {
                                    // Error parsing timestamp, skip check
                                }
                                break;
                            }
                            field_index++;
                        }
                        
                        // Check if the timestamp is within the last 60 seconds
                        if (!timestamp_parsed || (now_ms - client_epoch_ms < 60000)) {  // 60 seconds in ms
                            lines_to_append.push_back(line);
                            std::cout << "[DEBUG] Adding log entry: " 
                                    << line.substr(0, std::min(size_t(50), line.length())) 
                                    << (line.length() > 50 ? "..." : "") << std::endl;
                        } else {
                            std::cout << "[CONTROL] Filtered out old log entry: " 
                                    << line.substr(0, std::min(line.length(), (size_t)50)) << "..."
                                    << std::endl;
                        }
                    }
                    
                    // Append the filtered complete lines
                    for (const auto& valid_line : lines_to_append) {
                        append_csv_logs(valid_line);
                    }
                    
                    std::cout << "[DEBUG] Processed " << lines_to_append.size() << " complete lines" << std::endl;
                    if (!incomplete_line_buffer.empty()) {
                        std::cout << "[DEBUG] Saved 1 incomplete line for next request" << std::endl;
                    }
                }
                
                response = createResponse(200, "text/plain", "OK: csv logs appended\n");
            }

            else {
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