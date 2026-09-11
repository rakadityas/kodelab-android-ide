/*
 * Kodelab PTY shim — first-party code, written from the POSIX pty/termios
 * man pages and Android bionic headers (pty.h is available since API 23).
 * No code from Termux or any other GPL project is used here (see docs/IP-SAFETY.md).
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static char **to_c_array(JNIEnv *env, jobjectArray jarr) {
    if (jarr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, jarr);
    char **arr = calloc((size_t) n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, jarr, i);
        const char *cs = (*env)->GetStringUTFChars(env, js, NULL);
        arr[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, js, cs);
        (*env)->DeleteLocalRef(env, js);
    }
    arr[n] = NULL;
    return arr;
}

static void free_c_array(char **arr) {
    if (!arr) return;
    for (char **p = arr; *p; p++) free(*p);
    free(arr);
}

/*
 * Fork a child on a fresh pseudo-terminal and exec argv[0].
 * Returns the master fd, or -errno on failure. The child's pid is written
 * into outPid[0].
 */
JNIEXPORT jint JNICALL
Java_dev_kodelab_ide_terminal_Pty_forkExec(
        JNIEnv *env, jclass clazz,
        jobjectArray jargv, jobjectArray jenvp, jstring jcwd,
        jint rows, jint cols, jintArray joutPid) {
    (void) clazz;

    char **argv = to_c_array(env, jargv);
    char **envp = to_c_array(env, jenvp);
    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : NULL;

    struct winsize ws = {
            .ws_row = (unsigned short) (rows > 0 ? rows : 24),
            .ws_col = (unsigned short) (cols > 0 ? cols : 80),
    };

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        int err = errno;
        free_c_array(argv);
        free_c_array(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return -err;
    }

    if (pid == 0) {
        /* child: fresh session on the slave tty, then exec */
        if (cwd && chdir(cwd) != 0) {
            /* fall through with the inherited cwd rather than dying */
        }
        signal(SIGPIPE, SIG_DFL);
        if (envp) {
            execve(argv[0], argv, envp);
        } else {
            execv(argv[0], argv);
        }
        _exit(127);
    }

    /* parent */
    if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    free_c_array(argv);
    free_c_array(envp);

    jint pidOut = (jint) pid;
    (*env)->SetIntArrayRegion(env, joutPid, 0, 1, &pidOut);
    return master;
}

JNIEXPORT void JNICALL
Java_dev_kodelab_ide_terminal_Pty_resize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env; (void) clazz;
    struct winsize ws = {
            .ws_row = (unsigned short) rows,
            .ws_col = (unsigned short) cols,
    };
    ioctl(fd, TIOCSWINSZ, &ws);
}

/* Blocks until the child exits; returns its exit code (or -signal). */
JNIEXPORT jint JNICALL
Java_dev_kodelab_ide_terminal_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -errno;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL
Java_dev_kodelab_ide_terminal_Pty_kill(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    kill((pid_t) pid, SIGKILL);
}
