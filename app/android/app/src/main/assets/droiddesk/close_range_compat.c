/*
 * DroidDesk rooted Ubuntu compatibility shim.
 *
 * Some Android 5.4-derived kernels reject close_range(3, UINT_MAX, 0) with
 * EINVAL. GLib already has a safe fallback for kernels without close_range;
 * returning ENOSYS deliberately selects that fallback.
 */
#include <errno.h>

int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first;
    (void) last;
    (void) flags;
    errno = ENOSYS;
    return -1;
}
