// ==Pack==
// @id hello-js
// @name Hello JS
// @version 1.0.0
// @versionCode 1
// @packLib >=1.0.0 <2.0.0
// @description Minimal single-file JS pack sample
// @capability SELF_TEST
// @command hello
// @command echo
// @selfTest smoke hello
// ==/Pack==
// Body below is stored by the importer as main.js (PackArchiveSpec.SINGLE_FILE_JS_ENTRY).
globalThis.__packExports = {
    hello: function (arg) {
        return { ok: true, runtime: "js", echo: arg === null ? null : arg };
    },
    echo: function (arg) {
        return arg;
    },
};
