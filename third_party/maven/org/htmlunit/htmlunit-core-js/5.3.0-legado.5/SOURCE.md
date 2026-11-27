# htmlunit-core-js 5.3.0-legado.5

Class-level minSdk-23 patch of `5.3.0-legado.4`. The base binary is built
from the following public source revisions (unchanged):

- Packaging: `mgz0227/htmlunit-core-js@3eb5071cdca4a357119d1063a53d5f2d47984ed2`
- Rhino: `mgz0227/htmlunit-rhino-fork@76460c0312bfd351df6f2bb11168102cdb54170a`
- Base jar: `third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.4/htmlunit-core-js-5.3.0-legado.4.jar`
  (SHA-256 `d720f34285515025e4ccc80a5b92aed42cb26c03a7715ca53583d2c74ae51df7`)

## What changed (single source file)

`SlotMapOwner` (source: the Rhino fork revision above, put through the same
packaging rewrites the fork applies — see "Packaging transformations
applied" below): the nested class `ThreadedAccess` built a
`static VarHandle SLOT_MAP` via `MethodHandles.lookup().findVarHandle(...)`
and performed `SLOT_MAP.compareAndExchange(owner, oldMap, newMap)`. That
signature-polymorphic `MethodHandle` invocation requires Android API 26+,
and D8 diagnoses `MethodHandle.invoke and MethodHandle.invokeExact are only
supported starting with Android O (--min-api 26)` at the app's minSdk 23,
blocking `:app:assembleAppDebug` dexing.

The patch replaces the CAS with an equivalent `synchronized (owner)` block
that reads the current map via the package-private `getMap()`, compares with
`==`, and writes via `setMap()`, preserving the witness semantics of
`compareAndExchange` (returns `oldMap` on success, the current map on
failure). In this fork `createSlotMap` has the
`Context.FEATURE_THREAD_SAFE_OBJECTS` branch commented out ("we do not use
Context.FEATURE_THREAD_SAFE_OBJECTS"), so the patched path is unreachable
from legado; behavior of all reachable paths is unchanged.

Everything else in the jar is untouched: with the transformations below
applied, the rebuilt `SlotMapOwner` and all nested classes except
`ThreadedAccess` are bytecode-identical to the legado.4 jar (see
Verification), and `jar uf` replaces exactly those entries without adding
or removing any entry.

### Packaging transformations applied

The fork's packaging POM rewrites the Rhino source with
`maven-replacer-plugin` and compiles with `maven.compiler.release=17`. The
patched source applies every replacer rule that matches `SlotMapOwner.java`:

- `org.mozilla` → `org.htmlunit.corejs` (in declarations and javadoc
  references alike, exactly as the textual replacer does);
- `cx.hasFeature(Context.FEATURE_ENUMERATE_IDS_FIRST)` → `/* HtmlUnit
  cx.hasFeature(#Context.FEATURE_ENUMERATE_IDS_FIRST) */ true` in the
  key-array sorting path — the only replacer rule that both matches this
  file and affects bytecode. (`FEATURE_THREAD_SAFE_OBJECTS` has no
  replacement rule, and its branch is commented out in the fork source
  anyway.)

The source is compiled with `--release 17`, matching the fork's
`maven.compiler.release=17`, so nest-member access compiles exactly as in
legado.4 (nestmates attributes, no synthetic bridge classes). One
javac-17/21 codegen difference is neutralized at source level:
`addLazilyInitializedValue(Symbol, …)` calls `((Object) key).toString()` —
the explicit cast makes javac 21 emit the same
`invokevirtual java/lang/Object.toString` instruction that javac 17 emitted
into the legado.4 jar (an uncast interface-typed receiver compiles to
`invokeinterface Symbol.toString` on javac 21, which also changes every
following branch offset in the method).

The patched source is archived at
`app/src/main/assets/licenses/htmlunit-core-js/5.3.0-legado.5/SlotMapOwner.java`.

## Reproducible build (Git Bash)

```shell
export JAVA_HOME=/c/Users/ryz/Desktop/legado/tools/android-tools/jdk/jdk-21.0.13.1+2
export PATH="$JAVA_HOME/bin:$PATH"

# 1. compile the patched source against the legado.4 jar (emits 7 classes:
#    exactly the SlotMapOwner* entries of legado.4; --release 17 matches the
#    fork's maven.compiler.release=17)
javac --release 17 \
  -cp third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.4/htmlunit-core-js-5.3.0-legado.4.jar \
  -d build/classes \
  app/src/main/assets/licenses/htmlunit-core-js/5.3.0-legado.5/SlotMapOwner.java

# 2. start from the legado.4 jar and replace the SlotMapOwner entries.
#    Each file argument carries its own -C: the jar tool's -C changes the
#    directory for the immediately following input only.
cp third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.4/htmlunit-core-js-5.3.0-legado.4.jar \
   third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.5/htmlunit-core-js-5.3.0-legado.5.jar
jar uf third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.5/htmlunit-core-js-5.3.0-legado.5.jar \
  -C build/classes org/htmlunit/corejs/javascript/SlotMapOwner.class \
  -C build/classes 'org/htmlunit/corejs/javascript/SlotMapOwner$EmptySlotMap.class' \
  -C build/classes 'org/htmlunit/corejs/javascript/SlotMapOwner$Iter.class' \
  -C build/classes 'org/htmlunit/corejs/javascript/SlotMapOwner$SingleEntrySlotMap.class' \
  -C build/classes 'org/htmlunit/corejs/javascript/SlotMapOwner$ThreadSafeEmptySlotMap.class' \
  -C build/classes 'org/htmlunit/corejs/javascript/SlotMapOwner$ThreadSafeSingleEntrySlotMap.class' \
  -C build/classes 'org/htmlunit/corejs/javascript/SlotMapOwner$ThreadedAccess.class'
```

Independent D8 verification (do not use minSdk of the repo; this is a
standalone check):

```shell
java -cp <tools/gradle-home r8 jar> com.android.tools.r8.D8 --min-api 23 \
  --lib <tools/android-tools/sdk/platforms/android-36/android.jar> \
  --output build/dex \
  third_party/maven/org/htmlunit/htmlunit-core-js/5.3.0-legado.5/htmlunit-core-js-5.3.0-legado.5.jar
```

`jar uf` stores current timestamps, so the jar container is not
byte-reproducible; the 7 class files themselves are deterministic for a
fixed JDK.

## Verification (2026-11-27, JDK 21.0.13.1+2, R8 9.1.29)

- `javac --release 17` exit 0; 7 class files produced (no synthetic `$1`
  bridge class: nest-member access compiles as in legado.4).
- Entry-name diff vs the legado.4 jar: identical entry list (873 entries
  including directories); the 7 `SlotMapOwner*` entries are replaced,
  nothing is added or removed.
- Normalized `javap -p -c` diff vs legado.4 (constant-pool indices and
  whitespace normalized): `SlotMapOwner` and the nested `Iter`,
  `SingleEntrySlotMap`, `ThreadSafeSingleEntrySlotMap`, `EmptySlotMap`,
  `ThreadSafeEmptySlotMap` are all identical (0 diff lines). The only
  bytecode difference in the whole jar is `SlotMapOwner$ThreadedAccess`:
  it drops the VarHandle members, and `checkAndReplaceMap` becomes a
  `synchronized` block with `monitorenter`/`monitorexit` on every path
  (including the exceptional one), preserving the witness semantics.
- D8 `--min-api 23 --lib platforms/android-36/android.jar` on this jar:
  exit 0, dex produced, 0 `MethodHandle`/`min-api` diagnostics; the legado.4
  control jar shows exactly 1 such diagnostic (on
  `SlotMapOwner$ThreadedAccess.checkAndReplaceMap`). Remaining warnings are
  the pre-existing AWT/Swing "Type not found" warnings of the
  `tools/debugger` classes, identical in content to the control run.
- Follow-up gates with the jar wired into the build: `:app:assembleAppDebug`
  and `:modules:pack:test` exit 0; `:app:testAppDebugUnitTest` completes
  2121 tests with only the 2 pre-existing Windows-symlink failures.

## SHA-256

SHA-256 of `htmlunit-core-js-5.3.0-legado.5.jar`:

```text
8e2f4beb4350e1b4931cde258f3b76ec8e6f00d4774a9806dcbd7f56829981e4
```

Source changes, licenses, and notices are stored in
`app/src/main/assets/licenses/htmlunit-core-js/5.3.0-legado.5/` so they are
also distributed with the APK.
