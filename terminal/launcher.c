#define _GNU_SOURCE
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <signal.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>

static volatile sig_atomic_t group = 0;
static volatile sig_atomic_t stopped = 0;

static void stop(int signal) {
    stopped = signal;
    if (group > 0) kill(-group, SIGKILL);
}

static unsigned long long ticks(pid_t pid) {
    char path[64], buffer[4096];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    FILE *file = fopen(path, "r");
    if (!file) return 0;
    char *line = fgets(buffer, sizeof(buffer), file);
    fclose(file);
    if (!line) return 0;
    char *value = strrchr(buffer, ')');
    if (!value) return 0;
    char *save;
    value = strtok_r(value + 2, " ", &save);
    for (int field = 3; value && field < 22; field++) value = strtok_r(NULL, " ", &save);
    return value ? strtoull(value, NULL, 10) : 0;
}

static void kill_children(void) {
    char path[96];
    snprintf(path, sizeof(path), "/proc/self/task/%d/children", getpid());
    FILE *file = fopen(path, "r");
    if (!file) return;
    int pid;
    while (fscanf(file, "%d", &pid) == 1) if (pid > 0) kill(pid, SIGKILL);
    fclose(file);
}

int main(int argc, char **argv) {
    if (argc != 4) return 64;
    pid_t parent = getppid();
    struct sigaction action = {0};
    action.sa_handler = stop;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) return 125;
    prctl(PR_SET_CHILD_SUBREAPER, 1);
    int ready[2];
    if (pipe2(ready, O_CLOEXEC)) return 125;
    pid_t child = fork();
    if (child < 0) return 125;
    if (!child) {
        close(ready[0]);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        if (setsid() < 0) _exit(125);
        if (write(ready[1], "1", 1) != 1) _exit(125);
        close(ready[1]);
        execl(argv[1], "mksh", argv[2], (char *)NULL);
        perror("Bundled shell exec");
        _exit(126);
    }
    close(ready[1]);
    char byte;
    ssize_t count;
    do { count = read(ready[0], &byte, 1); } while (count < 0 && errno == EINTR);
    close(ready[0]);
    group = child;
    if (stopped || count != 1) kill(-child, SIGKILL);
    char *part;
    if (asprintf(&part, "%s.part", argv[3]) < 0) { kill(-child, SIGKILL); return 125; }
    FILE *file = fopen(part, "w");
    if (!file) { kill(-child, SIGKILL); free(part); return 125; }
    fprintf(file, "{\"supervisorPid\":%d,\"parentPid\":%d,\"shellPid\":%d,\"processGroup\":%d,\"supervisorStartTicks\":%llu,\"shellStartTicks\":%llu}\n", getpid(), parent, child, child, ticks(getpid()), ticks(child));
    int failed = fflush(file) || fsync(fileno(file));
    failed |= fclose(file);
    if (failed || rename(part, argv[3])) { kill(-child, SIGKILL); free(part); return 125; }
    free(part);
    int status;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    int exit_code = waited < 0 ? 125 : WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    kill(-child, SIGKILL);
    do { kill_children(); waited = waitpid(-1, NULL, 0); } while (waited > 0 || (waited < 0 && errno == EINTR));
    if (stopped) return 128 + stopped;
    return exit_code;
}
