# htmlunit-core-js 5.3.0-legado.5

This binary is a class-level patch of `5.3.0-legado.4`, built from the
following public source revisions:

- Packaging: `mgz0227/htmlunit-core-js@3eb5071cdca4a357119d1063a53d5f2d47984ed2`
- Rhino: `mgz0227/htmlunit-rhino-fork@76460c0312bfd351df6f2bb11168102cdb54170a`

`rhinoDiff.txt` records the Rhino changes from upstream merge base
`46d0904a3b4a1adc014a0d53d66a91b699a548de` to the pinned fork revision.

Changes relative to 5.3.0-legado.4 (single file, `SlotMapOwner.java`):
`SlotMapOwner$ThreadedAccess.checkAndReplaceMap` used
`MethodHandles.lookup().findVarHandle(...)` plus
`VarHandle.compareAndExchange`, which requires Android API 26+ and is
diagnosed by D8 at minSdk 23 ("MethodHandle.invoke ... only supported
starting with Android O (--min-api 26)"). The patch replaces it with an
equivalent `synchronized (owner)` block using the package-private
`getMap()`/`setMap()` accessors, preserving the witness semantics (returns
`oldMap` on success, the current map on failure). In this fork the
`Context.FEATURE_THREAD_SAFE_OBJECTS` branch of `createSlotMap` is disabled,
so the patched path is unreachable from legado.

The patched source also applies the fork's packaging rewrites that match
this file (`org.mozilla` → `org.htmlunit.corejs` everywhere including
javadoc, and `cx.hasFeature(Context.FEATURE_ENUMERATE_IDS_FIRST)` → `true`
— the only bytecode-affecting replacer rule for this file), is compiled
with `--release 17` like the fork's `maven.compiler.release=17`, and uses
one explicit `((Object) key).toString()` cast so javac 21 emits the same
instruction javac 17 emitted for legado.4. With all of that, the rebuilt
`SlotMapOwner` and every nested class except `ThreadedAccess` are
bytecode-identical to the legado.4 jar; `ThreadedAccess` is the only
changed class. The source is archived in this directory as
`SlotMapOwner.java`.

Rebuild: compile `SlotMapOwner.java` with
`javac --release 17 -cp <legado.4 jar> -d build/classes`, copy the legado.4
jar to this version's name and `jar uf` the 7 resulting `SlotMapOwner*`
entries into it (each file argument with its own `-C build/classes`), then
verify with `java -cp <r8.jar> com.android.tools.r8.D8 --min-api 23 --lib
<android-36 android.jar> <new jar>`. The full command sequence and
verification record are in
`third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.5/SOURCE.md`.

SHA-256 of `htmlunit-core-js-5.3.0-legado.5.jar`:

```text
8e2f4beb4350e1b4931cde258f3b76ec8e6f00d4774a9806dcbd7f56829981e4
```

Licensing records from both source revisions are bundled in this APK asset
directory and preserved in the source tree:

- The packaging revision declares Apache License 2.0 in its POM, while its
  `LICENSE.txt` states that the generated JavaScript engine is provided under
  MPL 2.0.
- The Rhino revision is MPL 2.0 and includes additional third-party notices.
- `LICENSE-core-js.txt`, `LICENSE.txt`, `LICENSE-APACHE-2.0.txt`, `NOTICE.txt`,
  `NOTICE-tools.txt`, and `rhinoDiff.txt` contain those original records,
  referenced license texts, and source changes. The JAR itself does not embed
  them; Android packaging includes this directory unchanged in the APK assets.
- `SlotMapOwner.java` is the patched source file described above; like the
  rest of the Rhino-derived engine it is MPL 2.0.

The corresponding source remains available from the exact revisions above.
