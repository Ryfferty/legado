/* Negative device-test fixture: a valid Android ELF shared object WITHOUT the
 * pack_get_api_v1 entry symbol. The JNI bridge maps it and then fails at
 * dlsym, which is exactly the "mapped library is pinned as failed" path that
 * must latch per path instead of consuming registry slots on every retry.
 *
 * Build (see fixtures/README.md):
 *   clang --target=<triple> -shared -O2 -std=c11 -Wall -Wextra -Werror \
 *     -fvisibility=hidden broken_native.c \
 *     -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096 -Wl,-s \
 *     -o ../assets/runtime-fixtures/broken-native-<abi>.so
 */
int pack_negative_fixture_dummy(void) { return 0; }
