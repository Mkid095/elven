// Minimal MCP (stdio) driver: spawn the FIDScript MCP and call one tool.
// Usage: node mcp_call.js <toolName> '<jsonArgs>'
// Env is inherited from the parent (we export the INSTANT_* vars before invoking).
const { spawn } = require("child_process");

const tool = process.argv[2];
let argsJson = process.argv[3] || "{}";
if (argsJson.startsWith("@")) {
  const fs = require("fs");
  argsJson = fs.readFileSync(process.cwd() + "/" + argsJson.slice(1), "utf8");
}

const ENV = {
  ...process.env,
  INSTANT_ACCESS_TOKEN: "per_cfe922e5dab4b8b6b1acbbba5f61724f5ac2b46d29a87a9be90d6f17728b2796",
  INSTANT_API_HOST: "https://apiinstant.fidscript.com",
  INSTANT_DASH_HOST: "https://instant.fidscript.com",
  INSTANT_MAVEN_HOST: "https://instant.fidscript.com/maven",
  INSTANT_APP_ID: "ce219821-50b6-4ffd-9e9f-d6ec44e6b7e9",
};

const isWin = process.platform === "win32";
const PKG = "@fidscript/instant-mcp@0.6.1";
const child = spawn(
  isWin ? `npx -y "${PKG}"` : "npx",
  isWin ? [] : ["-y", PKG],
  {
    env: ENV,
    stdio: ["pipe", "pipe", "pipe"],
    shell: isWin,
    windowsHide: false,
  }
);

let buf = "";
const pending = new Map();
const stderr = [];

function send(obj) {
  child.stdin.write(JSON.stringify(obj) + "\n");
}

function rpc(id, method, params) {
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    send({ jsonrpc: "2.0", id, method, params });
  });
}

child.stdout.on("data", (d) => {
  buf += d.toString();
  let idx;
  while ((idx = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, idx).trim();
    buf = buf.slice(idx + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    if (msg.id !== undefined && pending.has(msg.id)) {
      const p = pending.get(msg.id);
      pending.delete(msg.id);
      if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
      else p.resolve(msg.result);
    }
  }
});
child.stderr.on("data", (d) => stderr.push(d.toString()));

(async () => {
  let init;
  try {
    init = await rpc(1, "initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "zcode-driver", version: "1.0.0" },
    });
  } catch (e) {
    console.error("INIT_FAILED: " + e.message + " | stderr=" + stderr.join("").slice(0, 1500));
    process.exit(2);
  }
  send({ jsonrpc: "2.0", method: "notifications/initialized" });

  if (tool === "__list__") {
    const res = await rpc(2, "tools/list", {});
    const names = (res.tools || []).map((t) => t.name);
    console.log("TOOLS(" + names.length + "): " + names.join(", "));
    process.exit(0);
  }

  const res = await rpc(3, "tools/call", { name: tool, arguments: JSON.parse(argsJson) });
  // MCP tools/call returns { content: [{type:"text",text:"..."}], isError }
  if (res && res.content) {
    for (const c of res.content) {
      if (c.type === "text") process.stdout.write(c.text + "\n");
    }
  } else {
    process.stdout.write(JSON.stringify(res, null, 2) + "\n");
  }
  if (res && res.isError) process.exitCode = 1;
  process.exit(0);
})().catch((e) => {
  console.error("CALL_ERROR: " + e.message);
  console.error("stderr=" + stderr.join("").slice(0, 2000));
  process.exit(3);
});
