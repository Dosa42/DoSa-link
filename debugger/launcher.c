#define _GNU_SOURCE
#include <sys/prctl.h>
#include <signal.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 4) return 64;
    pid_t parent = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) return 125;
    char stat[4096], path[96]; unsigned long long ticks = 0;
    snprintf(path, sizeof(path), "/proc/%d/stat", getpid()); FILE *input = fopen(path, "r");
    if (input) {
        if (fgets(stat, sizeof(stat), input)) { char *end = strrchr(stat, ')'), *save; if (end) { char *value = strtok_r(end + 2, " ", &save); for (int i = 3; value && i < 22; ++i) value = strtok_r(NULL, " ", &save); if (value) ticks = strtoull(value, NULL, 10); } }
        fclose(input);
    }
    FILE *output = fopen(argv[1], "w"); if (!output) return 125;
    fprintf(output, "{\"pid\":%d,\"parentPid\":%d,\"startTicks\":%llu}\n", getpid(), parent, ticks);
    if (fclose(output)) return 125;
    execv(argv[2], argv + 2); perror("LLDB server exec"); return 126;
}
