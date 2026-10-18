// Android port: unpacks downloaded mods (zip, 7z, rar including RAR5, tar...) with libarchive,
// for the launcher's mod installer and GameBanana downloads. Entries are written below the destination folder
// only: absolute paths and ".." are refused, links and devices are skipped.

#ifdef __ANDROID__
#include <jni.h>
#endif
#include <archive.h>
#include <archive_entry.h>

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int make_dirs(char* path)
{
    for (char* p = path + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            if (mkdir(path, 0775) != 0 && errno != EEXIST) {
                *p = '/';
                return -1;
            }
            *p = '/';
        }
    }
    return 0;
}

// Normalises an entry name into a safe relative path; returns 0 when it must be skipped.
static int clean_name(const char* name, char* out, size_t size)
{
    size_t n = 0;
    const char* p = name;
    while (*p == '/' || *p == '\\')
        p++; // no absolute paths
    while (*p && n + 1 < size) {
        // Read one path component.
        const char* start = p;
        while (*p && *p != '/' && *p != '\\')
            p++;
        size_t len = (size_t)(p - start);
        while (*p == '/' || *p == '\\')
            p++;
        if (len == 0 || (len == 1 && start[0] == '.'))
            continue;
        if (len == 2 && start[0] == '.' && start[1] == '.')
            return 0; // refuse to climb out
        if (len == 2 && start[1] == ':')
            continue; // drive letters
        if (n + len + 2 >= size)
            return 0;
        if (n > 0)
            out[n++] = '/';
        memcpy(out + n, start, len);
        n += len;
    }
    out[n] = 0;
    return n > 0;
}

typedef int (*progress_fn)(void* user, long long done, long long total);

// Returns 0 on success; otherwise writes a message into error.
int modarchive_extract(const char* archivePath, const char* destination, progress_fn progress, void* user,
                       char* error, size_t errorSize)
{
    struct stat st;
    long long total = stat(archivePath, &st) == 0 ? (long long)st.st_size : 0;

    struct archive* a = archive_read_new();
    archive_read_support_format_all(a);
    archive_read_support_filter_all(a);

    error[0] = 0;
    char* buffer = malloc(1 << 20);
    if (archive_read_open_filename(a, archivePath, 1 << 16) != ARCHIVE_OK) {
        snprintf(error, errorSize, "Can't open the archive: %s", archive_error_string(a));
        goto done;
    }

    long long lastReport = 0;
    int files = 0;
    for (;;) {
        struct archive_entry* entry;
        int r = archive_read_next_header(a, &entry);
        if (r == ARCHIVE_EOF)
            break;
        if (r < ARCHIVE_WARN) {
            snprintf(error, errorSize, "The archive is damaged or unsupported: %s", archive_error_string(a));
            goto done;
        }

        const char* name = archive_entry_pathname_utf8(entry);
        if (!name)
            name = archive_entry_pathname(entry);
        char relative[2048];
        if (!name || !clean_name(name, relative, sizeof(relative)))
            continue;

        char path[4096];
        snprintf(path, sizeof(path), "%s/%s", destination, relative);
        mode_t type = archive_entry_filetype(entry);
        if (type == AE_IFDIR) {
            strcat(path, "/");
            make_dirs(path);
            continue;
        }
        if (type != AE_IFREG && type != 0)
            continue; // links, devices
        if (make_dirs(path) != 0) {
            snprintf(error, errorSize, "Can't create the folder for %s (%s)", relative, strerror(errno));
            goto done;
        }

        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0664);
        if (fd < 0) {
            snprintf(error, errorSize, "Can't write %s (%s)", relative, strerror(errno));
            goto done;
        }
        for (;;) {
            la_ssize_t n = archive_read_data(a, buffer, 1 << 20);
            if (n == 0)
                break;
            if (n < 0) {
                snprintf(error, errorSize, "Can't unpack %s: %s", relative, archive_error_string(a));
                close(fd);
                goto done;
            }
            for (la_ssize_t off = 0; off < n;) {
                ssize_t w = write(fd, buffer + off, (size_t)(n - off));
                if (w <= 0) {
                    snprintf(error, errorSize, "Can't write %s (%s)", relative, strerror(errno));
                    close(fd);
                    goto done;
                }
                off += w;
            }
            long long consumed = archive_filter_bytes(a, -1);
            if (progress && consumed - lastReport >= (1 << 20)) {
                lastReport = consumed;
                if (!progress(user, consumed, total)) {
                    snprintf(error, errorSize, "Cancelled");
                    close(fd);
                    goto done;
                }
            }
        }
        close(fd);
        files++;
    }
    if (files == 0)
        snprintf(error, errorSize, "The archive has no files");

done:
    free(buffer);
    archive_read_free(a);
    return error[0] ? -1 : 0;
}

#ifdef __ANDROID__
struct jni_progress {
    JNIEnv* env;
    jobject listener;
    jmethodID method;
};

static int jni_progress_cb(void* user, long long done, long long total)
{
    struct jni_progress* p = user;
    jboolean keep = (*p->env)->CallBooleanMethod(p->env, p->listener, p->method, (jlong)done, (jlong)total);
    if ((*p->env)->ExceptionCheck(p->env)) {
        (*p->env)->ExceptionClear(p->env);
        return 0;
    }
    return keep;
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_restuff_mods_ArchiveExtractor_nativeExtract(JNIEnv* env, jclass cls, jstring jarchive,
                                                              jstring jdestination, jobject listener)
{
    const char* archivePath = (*env)->GetStringUTFChars(env, jarchive, NULL);
    const char* destination = (*env)->GetStringUTFChars(env, jdestination, NULL);
    struct jni_progress progress = { env, listener, NULL };
    if (listener) {
        jclass listenerClass = (*env)->GetObjectClass(env, listener);
        progress.method = (*env)->GetMethodID(env, listenerClass, "onProgress", "(JJ)Z");
    }
    char error[1024];
    int result = modarchive_extract(archivePath, destination, progress.method ? jni_progress_cb : NULL, &progress,
                                    error, sizeof(error));
    (*env)->ReleaseStringUTFChars(env, jarchive, archivePath);
    (*env)->ReleaseStringUTFChars(env, jdestination, destination);
    return result == 0 ? NULL : (*env)->NewStringUTF(env, error);
}
#endif
