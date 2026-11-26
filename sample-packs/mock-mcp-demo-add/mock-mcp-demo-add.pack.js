// ==Pack==
// @id mock-mcp-demo-add
// @name Mock MCP demo_add
// @version 1.0.0
// @versionCode 1
// @packLib >=1.0.0 <2.0.0
// @description Registers the demo_add MCP tool (read-only)
// @capability MCP_TOOLS
// @command add
// @mcpTool demo_add add {"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}
// ==/Pack==
globalThis.__packExports = {
    add: function (arg) {
        var a = arg && typeof arg.a === "number" ? arg.a : 0;
        var b = arg && typeof arg.b === "number" ? arg.b : 0;
        return { sum: a + b, a: a, b: b };
    },
};
