# Compilation Instructions for Optimized TCP Server

## Required Dependencies
- C++11 compiler (g++ or clang++)
- nlohmann/json library
- POSIX libraries (pthread, etc.)

## Additional Include Files
```cpp
// Add these to the top of your main file
#include <fcntl.h>      // For fcntl, O_NONBLOCK
#include <netinet/tcp.h> // For TCP_NODELAY, TCP_CORK
#include <sys/epoll.h>   // For epoll functions
```

## Compilation Command
```bash
g++ -std=c++17 -O3 -march=native -o tcp_server tcp_server.cpp -pthread -I/usr/local/include -lstdc++fs

```

### Explanation of Optimization Flags:
- `-O3`: Enables level 3 optimization (most aggressive)
- `-march=native`: Optimizes for the current CPU architecture
- `-pthread`: Links pthread library for threading support
- `-I/usr/local/include`: Include path for nlohmann/json headers

## Installing Dependencies (Ubuntu/Debian)
```bash
sudo apt-get update
sudo apt-get install -y build-essential cmake libssl-dev git

# Install nlohmann/json library
git clone https://github.com/nlohmann/json.git
cd json
mkdir build && cd build
cmake ..
sudo make install
cd ../..
```
