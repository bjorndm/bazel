// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "blaze_util.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pwd.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <sstream>

#include "blaze_exit_code.h"
#include "util/numbers.h"
#include "util/strings.h"

using std::vector;

namespace blaze {

void die(const int exit_status, const char *format, ...) {
  va_list ap;
  va_start(ap, format);
  vfprintf(stderr, format, ap);
  va_end(ap);
  exit(exit_status);
}

void pdie(const int exit_status, const char *format, ...) {
  fprintf(stderr, "Error: ");
  va_list ap;
  va_start(ap, format);
  vfprintf(stderr, format, ap);
  va_end(ap);
  fprintf(stderr, ": %s\n", strerror(errno));
  exit(exit_status);
}

string GetUserName() {
  const char *user = getenv("USER");
  if (user && user[0] != '\0') return user;
  errno = 0;
  passwd *pwent = getpwuid(getuid());  // NOLINT (single-threaded)
  if (pwent == NULL || pwent->pw_name == NULL) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR,
         "$USER is not set, and unable to look up name of current user");
  }
  return pwent->pw_name;
}

// Returns the given path in absolute form.  Does not change paths that are
// already absolute.
//
// If called from working directory "/bar":
//   MakeAbsolute("foo") --> "/bar/foo"
//   MakeAbsolute("/foo") ---> "/foo"
string MakeAbsolute(string path) {
  // Check if path is already absolute.
  if (path.empty() || path[0] == '/') {
    return path;
  }

  char cwdbuf[PATH_MAX];
  if (getcwd(cwdbuf, sizeof cwdbuf) == NULL) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "getcwd() failed");
  }

  // Determine whether the cwd ends with "/" or not.
  string separator = (cwdbuf[strlen(cwdbuf) - 1] == '/') ? "" : "/";
  return cwdbuf + separator + path;
}

// mkdir -p path.  Returns -1 on failure, sets errno.
int MakeDirectories(string path, int mode) {
  path.push_back('\0');
  char *buf = &path[0];
  for (char *slash = strchr(buf + 1, '/'); slash != NULL;
       slash = strchr(slash + 1, '/')) {
    *slash = '\0';
    if (mkdir(buf, mode) == -1 && errno != EEXIST) {
      return -1;
    }
    *slash = '/';
  }
  // TODO(bazel-team):  EEXIST does not prove that it's a directory!
  if (mkdir(buf, mode) == -1 && errno != EEXIST) {
    return -1;
  }
  return 0;
}

// Replaces 'content' with contents of file 'filename'.
// Returns false on error.
bool ReadFile(const string &filename, string *content) {
  content->clear();
  int fd = open(filename.c_str(), O_RDONLY);
  if (fd == -1) return false;
  char buf[4096];
  // OPT:  This loop generates one spurious read on regular files.
  while (int r = read(fd, buf, sizeof buf)) {
    if (r == -1) {
      if (errno == EINTR) continue;
      return false;
    }
    content->append(buf, r);
  }
  close(fd);
  return true;
}

// Writes 'content' into file 'filename', and makes it executable.
// Returns false on failure, sets errno.
bool WriteFile(const string &content, const string &filename) {
  unlink(filename.c_str());
  int fd = open(filename.c_str(), O_CREAT|O_WRONLY|O_TRUNC, 0755);  // chmod +x
  if (fd == -1) return false;
  int r = write(fd, content.data(), content.size());
  int saved_errno = errno;
  if (close(fd)) return false;  // Can fail on NFS.
  errno = saved_errno;  // Caller should see errno from write().
  return r == content.size();
}

// Returns true iff both stdout and stderr are connected to a
// terminal, and it can support color and cursor movement
// (this is computed heuristically based on the values of
// environment variables).
bool IsStandardTerminal() {
  string term = getenv("TERM") == nullptr ? "" : getenv("TERM");
  string emacs = getenv("EMACS") == nullptr ? "" : getenv("EMACS");
  if (term == "" || term == "dumb" || term == "emacs" || term == "xterm-mono" ||
      term == "symbolics" || term == "9term" || emacs == "t") {
    return false;
  }
  return isatty(STDOUT_FILENO) && isatty(STDERR_FILENO);
}

// Returns the number of columns of the terminal to which stdout is
// connected, or $COLUMNS (default 80) if there is no such terminal.
int GetTerminalColumns() {
  struct winsize ws;
  if (ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) != -1) {
    return ws.ws_col;
  }
  const char* columns_env = getenv("COLUMNS");
  if (columns_env != NULL && columns_env[0] != '\0') {
    char* endptr;
    int columns = blaze_util::strto32(columns_env, &endptr, 10);
    if (*endptr == '\0') {  // $COLUMNS is a valid number
      return columns;
    }
  }
  return 80;  // default if not a terminal.
}

// Replace the current process with the given program in the given working
// directory, using the given argument vector.
// This function does not return on success.
void ExecuteProgram(string exe, const vector<string>& args_vector) {
  // Copy to a char* array for execv:
  int n = args_vector.size();
  const char **argv = new const char *[n + 1];
  for (int i = 0; i < n; ++i) {
    argv[i] = args_vector[i].c_str();
  }
  argv[n] = NULL;

  execv(exe.c_str(), const_cast<char**>(argv));
}

// Re-execute the blaze command line with a different binary as argv[0].
// This function does not return on success.
void ReExecute(const string &executable, int argc, const char *argv[]) {
  vector<string> args;
  args.push_back(executable);
  for (int i = 1; i < argc; i++) {
    args.push_back(argv[i]);
  }
  ExecuteProgram(args[0], args);
}

const char* GetUnaryOption(const char *arg, const char *next_arg,
                                  const char *key) {
  const char *value = blaze_util::var_strprefix(arg, key);
  if (value == NULL) {
    return NULL;
  } else if (value[0] == '=') {
    return value + 1;
  } else if (value[0]) {
    return NULL;  // trailing garbage in key name
  } else {
    return next_arg;
  }
}

bool GetNullaryOption(const char *arg, const char *key) {
  const char *value = blaze_util::var_strprefix(arg, key);
  if (value == NULL) {
    return false;
  } else if (value[0] == '=') {
    die(blaze_exit_code::BAD_ARGV,
        "In argument '%s': option '%s' does not take a value.\n", arg, key);
  } else if (value[0]) {
    return false;  // trailing garbage in key name
  } else {
    return true;
  }
}

void CheckValidPortOrDie(const string& str, const string& option) {
  int number;
  if (blaze_util::safe_strto32(str, &number) && number > 0 && number < 65536) {
    return;
  }

  die(blaze_exit_code::BAD_ARGV,
      "Invalid argument to %s: '%s' (must be a valid port number).\n",
      option.c_str(), str.c_str());
}

}  // namespace blaze
