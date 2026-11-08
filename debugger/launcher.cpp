#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <arpa/inet.h>
#include <poll.h>
#include <signal.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <set>
#include <fstream>
#include <sstream>
#include <chrono>

static volatile sig_atomic_t ending = 0;
static void stop(int) { ending = 1; }
static std::string directory;

static std::string quote(const std::string &value) {
    std::string output = "\"";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') { output += '\\'; output += c; }
        else if (c < 32) { char hex[7]; std::snprintf(hex, sizeof(hex), "\\u%04x", c); output += hex; }
        else output += c;
    }
    return output + "\"";
}
static void event(const std::string &direction, const std::string &packet) {
    std::ofstream output(directory + "/guardian-events.jsonl", std::ios::app);
    output << "{\"direction\":" << quote(direction) << ",\"packet\":" << quote(packet) << "}\n";
}
static bool send_all(int fd, const void *data, size_t count) {
    const char *bytes = static_cast<const char *>(data);
    while (count) { ssize_t n = write(fd, bytes, count); if (n < 0 && errno == EINTR) continue; if (n <= 0) return false; bytes += n; count -= n; }
    return true;
}
static std::string ticks(int pid) {
    std::ifstream input("/proc/" + std::to_string(pid) + "/stat"); std::string line, token; std::getline(input, line);
    auto end = line.rfind(')'); if (end == std::string::npos) return "0";
    std::istringstream fields(line.substr(end + 2));
    for (int i = 3; i <= 22; i++) if (!(fields >> token)) return "0";
    return token;
}
struct Packets {
    std::string buffer;
    bool pop(std::string &packet) {
        size_t start = buffer.find_first_of("$%");
        if (start == std::string::npos) { buffer.clear(); return false; }
        if (start) buffer.erase(0, start);
        size_t end = buffer.find('#', 1);
        if (end == std::string::npos || buffer.size() < end + 3) return false;
        packet = buffer.substr(1, end - 1); buffer.erase(0, end + 3); return true;
    }
};
struct Session {
    int fd;
    bool stopped = true, detached = false, no_ack = false;
    std::string last_request;
    std::set<std::string> points;
    Packets requests, replies;
    void request(const std::string &packet) {
        last_request = packet;
        if (packet.rfind("Z0,", 0) == 0) { size_t comma = packet.find(',', 3); if (comma != std::string::npos) points.insert(packet.substr(3, comma - 3)); }
        if (packet == "c" || packet == "s" || packet.rfind("vCont;", 0) == 0 || packet.rfind("C", 0) == 0) stopped = false;
    }
    void response(const std::string &packet) {
        if (!packet.empty() && (packet[0] == 'T' || packet[0] == 'S')) stopped = true;
        if (packet == "OK" && last_request == "QStartNoAckMode") no_ack = true;
        if (packet == "OK" && last_request == "D") detached = true;
    }
    bool send(const std::string &packet) {
        unsigned checksum = 0; for (unsigned char c : packet) checksum = (checksum + c) & 255;
        char suffix[4]; std::snprintf(suffix, sizeof(suffix), "#%02x", checksum);
        std::string framed = "$" + packet + suffix; event("cleanup.request", packet); request(packet);
        return send_all(fd, framed.data(), framed.size());
    }
    bool receive(std::string &packet, int timeout_ms) {
        auto limit = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
        for (;;) {
            if (replies.pop(packet)) { if (!no_ack) send_all(fd, "+", 1); response(packet); event("cleanup.response", packet); return true; }
            auto left = std::chrono::duration_cast<std::chrono::milliseconds>(limit - std::chrono::steady_clock::now()).count();
            if (left <= 0) return false;
            pollfd pending = {fd, POLLIN, 0}; int ready = poll(&pending, 1, static_cast<int>(left));
            if (ready < 0 && errno == EINTR) continue;
            if (ready <= 0) return false;
            char data[16384]; ssize_t count = read(fd, data, sizeof(data)); if (count <= 0) return false;
            replies.buffer.append(data, count);
        }
    }
    bool command(const std::string &packet) {
        if (!send(packet)) return false;
        std::string response;
        while (receive(response, 5000)) if (response == "OK") return true; else if (!response.empty() && response[0] == 'E') return false;
        return false;
    }
    bool cleanup() {
        if (detached) return true;
        if (!stopped) {
            if (!send_all(fd, "\003", 1)) return false;
            std::string reply;
            while (!stopped) if (!receive(reply, 5000)) return false;
        }
        for (const auto &address : points) {
            if (!send("z0," + address + ",4")) return false;
            std::string reply; bool answered = false;
            while (receive(reply, 5000)) if (reply == "OK" || (!reply.empty() && reply[0] == 'E')) { answered = true; break; }
            if (!answered) return false;
        }
        return command("D");
    }
};

int main(int argc, char **argv) {
    if (argc != 5) return 64;
    pid_t parent = getppid();
    struct sigaction action = {}; action.sa_handler = stop; sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, nullptr); sigaction(SIGINT, &action, nullptr); sigaction(SIGHUP, &action, nullptr); signal(SIGPIPE, SIG_IGN);
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) return 125;
    std::string identity = argv[1]; size_t slash = identity.rfind('/'); directory = slash == std::string::npos ? "." : identity.substr(0, slash);
    int pair[2]; if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair)) return 125;
    int client = socket(AF_INET, SOCK_STREAM, 0); if (client < 0) return 125;
    std::string endpoint = argv[3]; size_t colon = endpoint.rfind(':'); if (colon == std::string::npos) return 64;
    sockaddr_in remote = {}; remote.sin_family = AF_INET; remote.sin_addr.s_addr = htonl(INADDR_LOOPBACK); remote.sin_port = htons(std::atoi(endpoint.substr(colon + 1).c_str()));
    if (connect(client, reinterpret_cast<sockaddr *>(&remote), sizeof(remote))) return 125;
    pid_t child = fork(); if (child < 0) return 125;
    if (!child) {
        signal(SIGTERM, SIG_DFL); signal(SIGINT, SIG_DFL); signal(SIGHUP, SIG_DFL); close(pair[0]); close(client);
        char descriptor[32]; std::snprintf(descriptor, sizeof(descriptor), "%d", pair[1]);
        execl(argv[2], "lldb-server", "gdbserver", "--fd", descriptor, "--attach", argv[4], static_cast<char *>(nullptr));
        perror("LLDB exec"); _exit(126);
    }
    close(pair[1]);
    std::string part = identity + ".part";
    { std::ofstream output(part); output << "{\"pid\":" << getpid() << ",\"parentPid\":" << parent << ",\"serverPid\":" << child << ",\"startTicks\":" << ticks(getpid()) << ",\"serverStartTicks\":" << ticks(child) << "}\n"; }
    std::rename(part.c_str(), identity.c_str());
    Session session = {}; session.fd = pair[0];
    bool controller_gone = false;
    while (!ending) {
        pollfd sources[2] = {{client, POLLIN, 0}, {pair[0], POLLIN, 0}};
        int ready = poll(sources, 2, -1); if (ready < 0 && errno == EINTR) continue; if (ready < 0) break;
        bool failed = false;
        for (int i = 0; i < 2; i++) {
            if (!sources[i].revents) continue;
            char data[65536]; ssize_t count = read(sources[i].fd, data, sizeof(data));
            if (count <= 0) { if (!i) controller_gone = true; failed = true; break; }
            std::string packet;
            Packets &parser = i ? session.replies : session.requests; parser.buffer.append(data, count);
            while (parser.pop(packet)) { if (i) session.response(packet); else session.request(packet); }
            if (!send_all(i ? client : pair[0], data, count)) { controller_gone = true; failed = true; break; }
        }
        if (failed) break;
    }
    controller_gone = controller_gone || ending;
    bool detached = session.cleanup();
    close(client); close(pair[0]);
    if (detached) kill(child, SIGTERM);
    else kill(child, SIGKILL);
    int status = 0; while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    std::string report = directory + "/guardian-report.json", temporary = report + ".part";
    { std::ofstream output(temporary); output << "{\"guardianPid\":" << getpid() << ",\"serverPid\":" << child << ",\"controllerGone\":" << (controller_gone ? "true" : "false") << ",\"detached\":" << (detached ? "true" : "false") << ",\"trackedSoftwareBreakpoints\":" << session.points.size() << "}\n"; }
    std::rename(temporary.c_str(), report.c_str());
    return detached ? 0 : 125;
}
