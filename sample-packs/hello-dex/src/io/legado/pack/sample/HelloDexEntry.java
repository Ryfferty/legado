// Real DEX entry source for the hello-dex sample pack.
// Must implement io.legado.pack.api.PackEntry from modules/pack (see README.md
// for the javac + d8 build steps). Mirrors the verified androidTest fixture
// modules/pack/src/androidTest/java/io/legado/pack/runtime/dex/fixture/HelloDexEntry.java.in.
package io.legado.pack.sample;

import io.legado.pack.api.CloseOutcome;
import io.legado.pack.api.PackCallContext;
import io.legado.pack.api.PackContext;
import io.legado.pack.api.PackEntry;

public final class HelloDexEntry implements PackEntry {
    private boolean initialized;

    public HelloDexEntry() { }

    @Override
    public void initialize(PackContext context, PackCallContext call) {
        call.checkActive(System.nanoTime());
        initialized = true;
    }

    @Override
    public String invoke(String command, String argJson, PackCallContext call) {
        call.checkActive(System.nanoTime());
        if (!initialized) {
            throw new IllegalStateException("not initialized");
        }
        if ("hello".equals(command)) {
            return "{\"ok\":true,\"runtime\":\"dex\"}";
        }
        if ("echo".equals(command)) {
            return argJson;
        }
        throw new IllegalArgumentException(command);
    }

    @Override
    public CloseOutcome close() {
        initialized = false;
        return CloseOutcome.Companion.released();
    }
}
