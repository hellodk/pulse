# Connecting a macOS Jenkins Agent Through Tailscale — ACLs, No DERP, and Ansible

When you run Jenkins on Kubernetes and want to add a Mac Mini as a build agent for iOS pipelines, you quickly hit a wall: the Mac Mini can't reach the Jenkins NodePort. In our setup, both machines are on the **same physical LAN**, yet three independent blocking layers conspired to prevent the connection. This post covers how we diagnosed each layer and fixed them cleanly — no iptables hacking, no manual web console clicking, everything through Ansible and the Tailscale API.

> **TL;DR:** Seven blocking layers stood between the Mac Mini and Jenkins. Three before the playbook ran (AP isolation, Tailscale ACL DROP, DERP relay), and four more discovered while running it (Tailscale API quirks, Kubernetes NetworkPolicy, JNLP port mismatch, disk space threshold). Fixed: one Ansible playbook, targeted API calls, a NetworkPolicy patch, and a `brew cleanup`. Zero iptables edits.

---

## The Setup

<!-- SVG 1: Network topology diagram -->
<svg viewBox="0 0 820 420" xmlns="http://www.w3.org/2000/svg" style="width:100%;max-width:820px;display:block;margin:2em auto;font-family:ui-monospace,monospace">
  <defs>
    <style>
      .node-box { rx:10; ry:10; stroke-width:2 }
      .node-title { font-size:13px; font-weight:700; fill:#e2e8f0 }
      .node-sub { font-size:11px; fill:#94a3b8 }
      .label-badge { rx:4; ry:4 }

      /* blocked line animation */
      .line-blocked { stroke-dasharray:8 5; animation:dash-blocked 1s linear infinite }
      @keyframes dash-blocked { to { stroke-dashoffset:-26 } }

      /* fixed direct line */
      .line-direct { stroke-dasharray:none }

      /* data packet animation */
      .pkt { animation:move-pkt 2.4s ease-in-out infinite }
      .pkt-derp { animation:move-derp 3.2s ease-in-out infinite }
      @keyframes move-pkt {
        0%   { transform:translate(0,0);   opacity:0 }
        10%  {                             opacity:1 }
        90%  {                             opacity:1 }
        100% { transform:translate(var(--dx),var(--dy)); opacity:0 }
      }
      @keyframes move-derp {
        0%   { transform:translate(0,0);   opacity:0 }
        8%   {                             opacity:1 }
        92%  {                             opacity:1 }
        100% { transform:translate(var(--dx),var(--dy)); opacity:0 }
      }

      /* pulse for blocked node */
      .x-mark { animation:x-pulse 1.8s ease-in-out infinite }
      @keyframes x-pulse {
        0%,100% { opacity:1 }
        50%     { opacity:0.25 }
      }

      /* tailscale brand gradient */
      .ts-gradient { fill:url(#tsGrad) }
    </style>
    <linearGradient id="tsGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#4f46e5"/>
      <stop offset="100%" stop-color="#7c3aed"/>
    </linearGradient>
    <filter id="glow">
      <feGaussianBlur stdDeviation="3" result="blur"/>
      <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
  </defs>

  <!-- background -->
  <rect width="820" height="420" fill="#0f172a" rx="12"/>

  <!-- title -->
  <text x="410" y="30" text-anchor="middle" font-size="14" font-weight="700" fill="#e2e8f0">Network Topology — Before &amp; After Fix</text>

  <!-- ── BEFORE panel ── -->
  <rect x="10" y="44" width="390" height="362" fill="#1e293b" rx="8" stroke="#334155" stroke-width="1"/>
  <text x="205" y="66" text-anchor="middle" font-size="12" fill="#f87171" font-weight="700">BEFORE — Three Blocking Layers</text>

  <!-- Router (before) -->
  <rect x="150" y="80" width="110" height="52" fill="#1e3a5f" class="node-box" stroke="#3b82f6" stroke-width="2"/>
  <text x="205" y="100" text-anchor="middle" class="node-title">🌐 Router</text>
  <text x="205" y="116" text-anchor="middle" class="node-sub">AP Isolation ON</text>

  <!-- AP isolation wall -->
  <line x1="72" y1="140" x2="338" y2="140" stroke="#ef4444" stroke-width="2.5" stroke-dasharray="6 3"/>
  <rect x="155" y="133" width="100" height="16" fill="#1e293b"/>
  <text x="205" y="145" text-anchor="middle" font-size="10" fill="#ef4444" font-weight="700">AP ISOLATION WALL</text>

  <!-- cylon (before) -->
  <rect x="30" y="158" width="140" height="70" fill="#1a2744" class="node-box" stroke="#60a5fa" stroke-width="2"/>
  <text x="100" y="178" text-anchor="middle" class="node-title">⚙️ cylon</text>
  <text x="100" y="194" text-anchor="middle" class="node-sub">192.168.1.10</text>
  <text x="100" y="210" text-anchor="middle" class="node-sub">:30881 :30500</text>

  <!-- Mac Mini (before) -->
  <rect x="240" y="158" width="140" height="70" fill="#1a2744" class="node-box" stroke="#f59e0b" stroke-width="2"/>
  <text x="310" y="178" text-anchor="middle" class="node-title">🍎 Mac Mini</text>
  <text x="310" y="194" text-anchor="middle" class="node-sub">192.168.1.64</text>
  <text x="310" y="210" text-anchor="middle" class="node-sub">ios-agent</text>

  <!-- blocked arrow Mac Mini → cylon (before) -->
  <line x1="240" y1="193" x2="170" y2="193" class="line-blocked" stroke="#ef4444" stroke-width="2.5"/>
  <text x="205" y="188" text-anchor="middle" font-size="10" fill="#ef4444" class="x-mark">✕</text>

  <!-- Tailscale Control Plane (before) -->
  <rect x="130" y="252" width="150" height="44" fill="#2d1f52" class="node-box" stroke="#7c3aed" stroke-width="2"/>
  <text x="205" y="272" text-anchor="middle" class="node-title">Tailscale Control</text>
  <text x="205" y="288" text-anchor="middle" class="node-sub">api.tailscale.com</text>

  <!-- ts-input DROP badge -->
  <rect x="140" y="308" width="130" height="20" fill="#7f1d1d" class="label-badge"/>
  <text x="205" y="322" text-anchor="middle" font-size="10" fill="#fca5a5" font-weight="700">ts-input: DROP 100.64/10</text>

  <!-- DERP relay (before) -->
  <ellipse cx="310" cy="272" rx="58" ry="28" fill="#1c1917" stroke="#f97316" stroke-width="2" stroke-dasharray="5 3"/>
  <text x="310" y="268" text-anchor="middle" font-size="11" fill="#fb923c" font-weight="700">☁ DERP blr</text>
  <text x="310" y="284" text-anchor="middle" font-size="10" fill="#94a3b8">Bangalore relay</text>

  <!-- Mac Mini → DERP (before) -->
  <line x1="310" y1="228" x2="310" y2="244" stroke="#f97316" stroke-width="2" stroke-dasharray="4 3" class="line-blocked"/>
  <!-- DERP → cylon (before) -->
  <path d="M 252 272 Q 180 310 140 228" fill="none" stroke="#f97316" stroke-width="2" stroke-dasharray="4 3" class="line-blocked"/>
  <text x="170" y="300" font-size="10" fill="#f97316">via cloud ⚠</text>

  <!-- ── AFTER panel ── -->
  <rect x="420" y="44" width="390" height="362" fill="#1e293b" rx="8" stroke="#334155" stroke-width="1"/>
  <text x="615" y="66" text-anchor="middle" font-size="12" fill="#4ade80" font-weight="700">AFTER — Direct LAN Connection</text>

  <!-- Router (after) -->
  <rect x="560" y="80" width="110" height="52" fill="#1e3a5f" class="node-box" stroke="#3b82f6" stroke-width="2"/>
  <text x="615" y="100" text-anchor="middle" class="node-title">🌐 Router</text>
  <text x="615" y="116" text-anchor="middle" class="node-sub">AP Isolation ✓ OFF</text>

  <!-- cylon (after) -->
  <rect x="440" y="158" width="140" height="70" fill="#1a2744" class="node-box" stroke="#60a5fa" stroke-width="2"/>
  <text x="510" y="178" text-anchor="middle" class="node-title">⚙️ cylon</text>
  <text x="510" y="194" text-anchor="middle" class="node-sub">192.168.1.10</text>
  <text x="510" y="210" text-anchor="middle" class="node-sub">:30881 :30500</text>

  <!-- Mac Mini (after) -->
  <rect x="650" y="158" width="140" height="70" fill="#1a2744" class="node-box" stroke="#4ade80" stroke-width="2" filter="url(#glow)"/>
  <text x="720" y="178" text-anchor="middle" class="node-title">🍎 Mac Mini</text>
  <text x="720" y="194" text-anchor="middle" class="node-sub">192.168.1.64</text>
  <text x="720" y="210" text-anchor="middle" class="node-sub">ios-agent ✓</text>

  <!-- direct arrow (after) — animated data packets -->
  <line x1="580" y1="193" x2="650" y2="193" stroke="#4ade80" stroke-width="3"/>
  <!-- packet 1 -->
  <circle cx="580" cy="193" r="5" fill="#4ade80" opacity="0">
    <animateMotion dur="1.8s" repeatCount="indefinite" begin="0s">
      <mpath href="#direct-path"/>
    </animateMotion>
    <animate attributeName="opacity" values="0;1;1;0" keyTimes="0;0.1;0.9;1" dur="1.8s" repeatCount="indefinite" begin="0s"/>
  </circle>
  <!-- packet 2 -->
  <circle cx="580" cy="193" r="5" fill="#4ade80" opacity="0">
    <animateMotion dur="1.8s" repeatCount="indefinite" begin="0.6s">
      <mpath href="#direct-path"/>
    </animateMotion>
    <animate attributeName="opacity" values="0;1;1;0" keyTimes="0;0.1;0.9;1" dur="1.8s" repeatCount="indefinite" begin="0.6s"/>
  </circle>
  <!-- packet 3 reverse -->
  <circle cx="650" cy="196" r="4" fill="#60a5fa" opacity="0">
    <animateMotion dur="1.8s" repeatCount="indefinite" begin="0.9s">
      <mpath href="#direct-path-rev"/>
    </animateMotion>
    <animate attributeName="opacity" values="0;1;1;0" keyTimes="0;0.1;0.9;1" dur="1.8s" repeatCount="indefinite" begin="0.9s"/>
  </circle>
  <path id="direct-path" d="M 580,193 L 650,193" fill="none"/>
  <path id="direct-path-rev" d="M 650,196 L 580,196" fill="none"/>

  <text x="615" y="183" text-anchor="middle" font-size="10" fill="#4ade80">LAN direct ~2ms</text>

  <!-- Tailscale Control (after) -->
  <rect x="540" y="252" width="150" height="44" fill="#2d1f52" class="node-box" stroke="#7c3aed" stroke-width="2"/>
  <text x="615" y="272" text-anchor="middle" class="node-title">Tailscale Control</text>
  <text x="615" y="288" text-anchor="middle" class="node-sub">api.tailscale.com</text>

  <!-- ACL ACCEPT badge -->
  <rect x="548" y="308" width="134" height="20" fill="#14532d" class="label-badge"/>
  <text x="615" y="322" text-anchor="middle" font-size="10" fill="#86efac" font-weight="700">ts-input: ACCEPT :30881,:30500</text>

  <!-- subnet route badge -->
  <rect x="548" y="332" width="134" height="20" fill="#1e3a5f" class="label-badge"/>
  <text x="615" y="346" text-anchor="middle" font-size="10" fill="#93c5fd" font-weight="700">subnet 192.168.1.0/24 via cylon</text>

  <!-- DERP (after - greyed out) -->
  <ellipse cx="720" cy="272" rx="58" ry="28" fill="#0f172a" stroke="#374151" stroke-width="1.5" stroke-dasharray="5 3" opacity="0.4"/>
  <text x="720" y="268" text-anchor="middle" font-size="11" fill="#6b7280" font-weight="700">☁ DERP blr</text>
  <text x="720" y="284" text-anchor="middle" font-size="10" fill="#4b5563">not used ✓</text>

  <!-- legend -->
  <g transform="translate(420,390)">
    <rect width="14" height="4" y="-3" fill="#ef4444" rx="2"/>
    <text x="18" y="1" font-size="10" fill="#94a3b8">Blocked</text>
    <rect x="80" width="14" height="4" y="-3" fill="#4ade80" rx="2"/>
    <text x="98" y="1" font-size="10" fill="#94a3b8">Direct LAN</text>
    <rect x="175" width="14" height="4" y="-3" fill="#f97316" rx="2"/>
    <text x="193" y="1" font-size="10" fill="#94a3b8">DERP relay (eliminated)</text>
  </g>
</svg>

Jenkins runs inside k0s. Its ports are exposed as Kubernetes NodePorts. The Mac Mini needs to reach two of them:

- **30881** — Jenkins HTTP API (agent registration, `agent.jar` download)
- **30500** — JNLP raw TCP (streams build logs, test results, and artifacts)

---

## Why the Mac Mini Couldn't Connect

Three independent blocking layers stacked up simultaneously.

<!-- SVG 2: ts-input chain animation -->
<svg viewBox="0 0 720 300" xmlns="http://www.w3.org/2000/svg" style="width:100%;max-width:720px;display:block;margin:2em auto;font-family:ui-monospace,monospace">
  <defs>
    <style>
      .chain-row { transition:all 0.5s }
      .drop-row { animation:drop-pulse 2s ease-in-out infinite }
      @keyframes drop-pulse {
        0%,100% { opacity:1 }
        50%      { opacity:0.5 }
      }
      .arrow-bounce { animation:arrow-b 1.4s ease-in-out infinite }
      @keyframes arrow-b {
        0%,100% { transform:translateX(0) }
        50%      { transform:translateX(6px) }
      }
    </style>
  </defs>
  <rect width="720" height="300" fill="#0f172a" rx="12"/>
  <text x="360" y="26" text-anchor="middle" font-size="13" font-weight="700" fill="#e2e8f0">Tailscale ts-input iptables Chain — The DROP Rule</text>

  <!-- column headers -->
  <text x="30"  y="50" font-size="10" fill="#64748b">RULE</text>
  <text x="80"  y="50" font-size="10" fill="#64748b">TARGET</text>
  <text x="200" y="50" font-size="10" fill="#64748b">SOURCE</text>
  <text x="420" y="50" font-size="10" fill="#64748b">COMMENT</text>
  <line x1="20" y1="55" x2="700" y2="55" stroke="#334155" stroke-width="1"/>

  <!-- Row 1: ACCEPT self -->
  <rect x="20" y="60" width="680" height="36" fill="#14532d" rx="4" opacity="0.4"/>
  <text x="40"  y="82" font-size="12" fill="#86efac">1</text>
  <text x="80"  y="82" font-size="12" fill="#4ade80" font-weight="700">ACCEPT</text>
  <text x="200" y="82" font-size="12" fill="#e2e8f0">100.89.50.27</text>
  <text x="420" y="82" font-size="12" fill="#94a3b8">← cylon itself (self-traffic)</text>

  <!-- Row 2: RETURN internal -->
  <rect x="20" y="100" width="680" height="36" fill="#1e3a5f" rx="4" opacity="0.4"/>
  <text x="40"  y="122" font-size="12" fill="#93c5fd">2</text>
  <text x="80"  y="122" font-size="12" fill="#60a5fa" font-weight="700">RETURN</text>
  <text x="200" y="122" font-size="12" fill="#e2e8f0">100.115.92.0/23</text>
  <text x="420" y="122" font-size="12" fill="#94a3b8">← Tailscale internal range</text>

  <!-- Row 3: DROP — animated pulsing red -->
  <rect x="20" y="140" width="680" height="44" fill="#7f1d1d" rx="4" class="drop-row"/>
  <rect x="20" y="140" width="680" height="44" fill="none" stroke="#ef4444" stroke-width="2" rx="4" class="drop-row"/>
  <text x="40"  y="166" font-size="13" fill="#fca5a5" font-weight="700">3</text>
  <text x="80"  y="166" font-size="13" fill="#ef4444" font-weight="900">DROP ◀</text>
  <text x="200" y="166" font-size="13" fill="#fecaca" font-weight="700">100.64.0.0/10</text>
  <text x="420" y="162" font-size="11" fill="#fca5a5">← ALL Tailscale peers blocked</text>
  <text x="420" y="178" font-size="10" fill="#f87171">100.102.68.75 (Mac Mini) is in this range!</text>
  <!-- arrow pointing to drop row -->
  <text x="665" y="168" font-size="16" fill="#ef4444" class="arrow-bounce">⚡</text>

  <!-- Row 4: ACCEPT rest -->
  <rect x="20" y="188" width="680" height="36" fill="#1e293b" rx="4" opacity="0.6"/>
  <text x="40"  y="210" font-size="12" fill="#64748b">4</text>
  <text x="80"  y="210" font-size="12" fill="#94a3b8" font-weight="700">ACCEPT</text>
  <text x="200" y="210" font-size="12" fill="#475569">0.0.0.0/0</text>
  <text x="420" y="210" font-size="12" fill="#64748b">← non-Tailscale traffic</text>

  <!-- Row 5: ACCEPT UDP -->
  <rect x="20" y="228" width="680" height="36" fill="#1e293b" rx="4" opacity="0.6"/>
  <text x="40"  y="250" font-size="12" fill="#64748b">5</text>
  <text x="80"  y="250" font-size="12" fill="#94a3b8" font-weight="700">ACCEPT</text>
  <text x="200" y="250" font-size="12" fill="#475569">0.0.0.0/0  UDP :41641</text>
  <text x="420" y="250" font-size="12" fill="#64748b">← Tailscale WireGuard port</text>

  <!-- note -->
  <text x="360" y="285" text-anchor="middle" font-size="10" fill="#64748b">Rule 3 is auto-generated by the Tailscale daemon from your ACL policy. Editing it manually gets overwritten every ~5 min.</text>
</svg>

### 1. AP Isolation on the Router

Many home and office routers enable "client isolation" on the wireless network. Devices can reach the gateway but not each other directly. The Ansible controller (cylon) could SSH *to* the Mac Mini fine — cylon initiates that connection outbound. But when the Mac Mini tried to initiate a connection *to* cylon's ports, the router silently dropped it.

### 2. Tailscale's Default ACL — the `ts-input` DROP Rule

As shown in the chain diagram above, when you have a custom ACL that doesn't explicitly allow peer-to-peer traffic on specific ports, the Tailscale daemon inserts a blanket `DROP 100.64.0.0/10` rule. The Mac Mini's Tailscale IP `100.102.68.75` is inside that CGNAT range. Result: Tailscale itself was blocking the Mac Mini from both sides.

The critical insight: **this chain is daemon-owned**. Any manual rule you insert gets silently overwritten the next time the daemon regenerates its policy (~5 minute cycle). The only durable fix is at the Tailscale control plane.

### 3. DERP Relay Instead of Direct Connection

<!-- SVG 3: DERP vs Direct animated traffic -->
<svg viewBox="0 0 760 240" xmlns="http://www.w3.org/2000/svg" style="width:100%;max-width:760px;display:block;margin:2em auto;font-family:ui-monospace,monospace">
  <defs>
    <style>
      .pkt-slow { r:5; fill:#f97316}
      .pkt-fast { r:5; fill:#4ade80}
    </style>
    <marker id="arr-red"  markerWidth="8" markerHeight="6" refX="6" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#f97316"/>
    </marker>
    <marker id="arr-grn"  markerWidth="8" markerHeight="6" refX="6" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#4ade80"/>
    </marker>
  </defs>
  <rect width="760" height="240" fill="#0f172a" rx="12"/>

  <!-- BEFORE label -->
  <text x="190" y="24" text-anchor="middle" font-size="12" fill="#f87171" font-weight="700">BEFORE — Via DERP Relay</text>
  <!-- divider -->
  <line x1="380" y1="16" x2="380" y2="224" stroke="#334155" stroke-width="1.5" stroke-dasharray="4 4"/>
  <!-- AFTER label -->
  <text x="570" y="24" text-anchor="middle" font-size="12" fill="#4ade80" font-weight="700">AFTER — Direct LAN Route</text>

  <!-- ── BEFORE side ── -->
  <!-- Mac Mini node -->
  <rect x="20"  y="100" width="100" height="50" fill="#1a2744" rx="8" stroke="#f59e0b" stroke-width="2"/>
  <text x="70"  y="122" text-anchor="middle" font-size="11" fill="#e2e8f0" font-weight="700">🍎 Mac Mini</text>
  <text x="70"  y="138" text-anchor="middle" font-size="10" fill="#94a3b8">100.102.68.75</text>

  <!-- DERP cloud -->
  <ellipse cx="190" cy="58" rx="70" ry="30" fill="#1c1917" stroke="#f97316" stroke-width="2" stroke-dasharray="5 3"/>
  <text x="190" y="54" text-anchor="middle" font-size="11" fill="#fb923c" font-weight="700">☁ DERP blr</text>
  <text x="190" y="70" text-anchor="middle" font-size="10" fill="#6b7280">Bangalore, India</text>

  <!-- cylon node -->
  <rect x="280" y="100" width="100" height="50" fill="#1a2744" rx="8" stroke="#60a5fa" stroke-width="2"/>
  <text x="330" y="122" text-anchor="middle" font-size="11" fill="#e2e8f0" font-weight="700">⚙ cylon</text>
  <text x="330" y="138" text-anchor="middle" font-size="10" fill="#94a3b8">100.89.50.27</text>

  <!-- path lines (before) -->
  <path d="M 120,110 Q 160,58 120,58" fill="none" stroke="#f97316" stroke-width="1.5" marker-end="url(#arr-red)" opacity="0.7"/>
  <path d="M 260,58 Q 300,58 280,115" fill="none" stroke="#f97316" stroke-width="1.5" marker-end="url(#arr-red)" opacity="0.7"/>
  <text x="85" y="80" font-size="9" fill="#f97316">+150ms</text>
  <text x="295" y="80" font-size="9" fill="#f97316">+150ms</text>

  <!-- animated packets (before) - going up to DERP then down to cylon -->
  <circle r="5" fill="#f97316" opacity="0">
    <animateMotion dur="3.2s" repeatCount="indefinite" begin="0s">
      <mpath href="#derp-path-fwd"/>
    </animateMotion>
    <animate attributeName="opacity" values="0;1;1;0" keyTimes="0;0.05;0.95;1" dur="3.2s" repeatCount="indefinite" begin="0s"/>
  </circle>
  <path id="derp-path-fwd" d="M 120,115 Q 155,58 190,58 Q 230,58 265,58 Q 310,70 310,115" fill="none"/>

  <!-- stat box before -->
  <rect x="70" y="172" width="260" height="40" fill="#1c1917" rx="6" stroke="#374151" stroke-width="1"/>
  <text x="200" y="190" text-anchor="middle" font-size="10" fill="#f87171">Relay: blr (Bangalore)</text>
  <text x="200" y="205" text-anchor="middle" font-size="10" fill="#94a3b8">All build data routed via cloud ⚠ ISP bandwidth</text>

  <!-- ── AFTER side ── -->
  <!-- Mac Mini node -->
  <rect x="400" y="100" width="100" height="50" fill="#1a2744" rx="8" stroke="#4ade80" stroke-width="2"/>
  <text x="450" y="122" text-anchor="middle" font-size="11" fill="#e2e8f0" font-weight="700">🍎 Mac Mini</text>
  <text x="450" y="138" text-anchor="middle" font-size="10" fill="#94a3b8">192.168.1.64</text>

  <!-- cylon node -->
  <rect x="660" y="100" width="100" height="50" fill="#1a2744" rx="8" stroke="#60a5fa" stroke-width="2"/>
  <text x="710" y="122" text-anchor="middle" font-size="11" fill="#e2e8f0" font-weight="700">⚙ cylon</text>
  <text x="710" y="138" text-anchor="middle" font-size="10" fill="#94a3b8">192.168.1.10</text>

  <!-- direct LAN line (after) -->
  <line x1="500" y1="125" x2="660" y2="125" stroke="#4ade80" stroke-width="2.5" marker-end="url(#arr-grn)"/>
  <text x="580" y="115" text-anchor="middle" font-size="9" fill="#4ade80">LAN direct ~2ms</text>

  <!-- DERP cloud (greyed) -->
  <ellipse cx="580" cy="58" rx="70" ry="28" fill="#0f172a" stroke="#374151" stroke-width="1" stroke-dasharray="4 4" opacity="0.4"/>
  <text x="580" y="54" text-anchor="middle" font-size="11" fill="#4b5563">☁ DERP blr</text>
  <text x="580" y="70" text-anchor="middle" font-size="10" fill="#374151">not used ✓</text>

  <!-- animated packets (after) -->
  <circle r="5" fill="#4ade80" opacity="0">
    <animateMotion dur="1.4s" repeatCount="indefinite" begin="0s">
      <mpath href="#direct-lan"/>
    </animateMotion>
    <animate attributeName="opacity" values="0;1;1;0" keyTimes="0;0.08;0.92;1" dur="1.4s" repeatCount="indefinite" begin="0s"/>
  </circle>
  <circle r="5" fill="#4ade80" opacity="0">
    <animateMotion dur="1.4s" repeatCount="indefinite" begin="0.47s">
      <mpath href="#direct-lan"/>
    </animateMotion>
    <animate attributeName="opacity" values="0;1;1;0" keyTimes="0;0.08;0.92;1" dur="1.4s" repeatCount="indefinite" begin="0.47s"/>
  </circle>
  <path id="direct-lan" d="M 500,125 L 660,125" fill="none"/>

  <!-- stat box after -->
  <rect x="400" y="172" width="360" height="40" fill="#14532d" rx="6" stroke="#166534" stroke-width="1"/>
  <text x="580" y="190" text-anchor="middle" font-size="10" fill="#4ade80">Relay: none — direct</text>
  <text x="580" y="205" text-anchor="middle" font-size="10" fill="#86efac">Build data stays on LAN. Zero cloud relay bandwidth.</text>
</svg>

Even for traffic that bypassed the ACL, the Mac Mini was routing *everything* through the Bangalore DERP relay instead of talking directly to cylon. The relay was used because the Mac Mini connected from an IPv6 address (`[2402:e280:...]`) while cylon had no matching publicly reachable IPv6 endpoint — UDP hole-punch failed, so Tailscale fell back to the nearest DERP server.

DERP relays are fine for tiny control messages (~100 bytes per keepalive). For a CI agent that streams build logs, test results, and binary artifacts, routing through a cloud relay adds real latency and burns ISP bandwidth on every build.

---

## The Fix — Three Parts, One Ansible Playbook

<!-- SVG 4: Ansible ACL Read-Modify-Write flow -->
<svg viewBox="0 0 760 180" xmlns="http://www.w3.org/2000/svg" style="width:100%;max-width:760px;display:block;margin:2em auto;font-family:ui-monospace,monospace">
  <defs>
    <style>
      .step-box { rx:8; ry:8; stroke-width:2 }
      .step-label { font-size:11px; font-weight:700; fill:#e2e8f0 }
      .step-sub { font-size:10px; fill:#94a3b8 }
      .flow-arrow { stroke-width:2.5; fill:none }

      /* step highlight animations — sequential */
      .s1 { animation:hl 3.6s ease-in-out infinite 0.0s }
      .s2 { animation:hl 3.6s ease-in-out infinite 1.2s }
      .s3 { animation:hl 3.6s ease-in-out infinite 2.4s }
      @keyframes hl {
        0%,25%,100% { opacity:1; filter:none }
        8%,17%      { opacity:1; filter:drop-shadow(0 0 8px currentColor) }
      }

      /* packet travelling along arrows */
      .traveller { animation:travel 3.6s linear infinite }
      @keyframes travel {
        0%   { offset-distance:0%;   opacity:0 }
        5%   {                        opacity:1 }
        45%  { offset-distance:100%; opacity:1 }
        50%  {                        opacity:0 }
        100% {                        opacity:0 }
      }
      .traveller2 { animation:travel 3.6s linear infinite 1.2s }
    </style>
  </defs>

  <rect width="760" height="180" fill="#0f172a" rx="12"/>
  <text x="380" y="24" text-anchor="middle" font-size="13" font-weight="700" fill="#e2e8f0">ACL Update — Ansible Read-Modify-Write Flow</text>

  <!-- Step 1: GET -->
  <rect x="20"  y="50" width="180" height="90" fill="#1e293b" class="step-box s1" stroke="#3b82f6" style="color:#3b82f6"/>
  <text x="110" y="74"  text-anchor="middle" class="step-label">① GET Current ACL</text>
  <text x="110" y="91"  text-anchor="middle" class="step-sub">GET /api/v2/tailnet/</text>
  <text x="110" y="106" text-anchor="middle" class="step-sub">{tailnet}/acl</text>
  <rect x="36"  y="115" width="148" height="16" fill="#1e3a5f" rx="3"/>
  <text x="110" y="127" text-anchor="middle" font-size="9" fill="#93c5fd">→ returns HuJSON policy</text>

  <!-- Arrow 1→2 -->
  <path id="arr1" d="M 200,95 L 285,95" stroke="#7c3aed" class="flow-arrow" marker-end="url(#arr-purple)"/>
  <defs>
    <marker id="arr-purple" markerWidth="8" markerHeight="6" refX="6" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#7c3aed"/>
    </marker>
    <marker id="arr-green2" markerWidth="8" markerHeight="6" refX="6" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6 Z" fill="#4ade80"/>
    </marker>
  </defs>
  <!-- packet 1 -->
  <circle r="5" fill="#7c3aed">
    <animateMotion dur="3.6s" repeatCount="indefinite" begin="0s" calcMode="linear">
      <mpath href="#arr1"/>
    </animateMotion>
    <animate attributeName="opacity" values="0;0;1;1;0;0" keyTimes="0;0.05;0.1;0.4;0.45;1" dur="3.6s" repeatCount="indefinite" begin="0s"/>
  </circle>

  <!-- Step 2: Modify -->
  <rect x="285" y="50" width="190" height="90" fill="#1e293b" class="step-box s2" stroke="#7c3aed" style="color:#7c3aed"/>
  <text x="380" y="74"  text-anchor="middle" class="step-label">② Prepend ACCEPT Rule</text>
  <text x="380" y="91"  text-anchor="middle" class="step-sub">Ansible combine() filter</text>
  <text x="380" y="106" text-anchor="middle" class="step-sub">→ add mac_mini:30881,30500</text>
  <rect x="300" y="115" width="160" height="16" fill="#2d1f52" rx="3"/>
  <text x="380" y="127" text-anchor="middle" font-size="9" fill="#c4b5fd">existing rules preserved</text>

  <!-- Arrow 2→3 -->
  <path id="arr2" d="M 475,95 L 555,95" stroke="#4ade80" class="flow-arrow" marker-end="url(#arr-green2)"/>
  <!-- packet 2 -->
  <circle r="5" fill="#4ade80">
    <animateMotion dur="3.6s" repeatCount="indefinite" begin="1.2s" calcMode="linear">
      <mpath href="#arr2"/>
    </animateMotion>
    <animate attributeName="opacity" values="0;0;1;1;0;0" keyTimes="0;0.05;0.1;0.4;0.45;1" dur="3.6s" repeatCount="indefinite" begin="1.2s"/>
  </circle>

  <!-- Step 3: POST + propagate -->
  <rect x="555" y="50" width="185" height="90" fill="#1e293b" class="step-box s3" stroke="#4ade80" style="color:#4ade80"/>
  <text x="647" y="74"  text-anchor="middle" class="step-label">③ POST + Propagate</text>
  <text x="647" y="91"  text-anchor="middle" class="step-sub">POST /api/v2/tailnet/</text>
  <text x="647" y="106" text-anchor="middle" class="step-sub">{tailnet}/acl</text>
  <rect x="570" y="115" width="160" height="16" fill="#14532d" rx="3"/>
  <text x="647" y="127" text-anchor="middle" font-size="9" fill="#86efac">daemon regenerates ts-input ✓</text>

  <!-- timeline labels -->
  <text x="200" y="156" text-anchor="middle" font-size="10" fill="#4b5563">~200ms</text>
  <text x="510" y="156" text-anchor="middle" font-size="10" fill="#4b5563">~200ms + daemon poll ~5min</text>
</svg>

The entire fix runs as an Ansible playbook on `localhost` (cylon). No SSH to the Mac Mini required for the network policy change.

### Part 1: Update the Tailscale ACL via API

Tailscale's CLI only manages the **local node** — routes, exit nodes, DNS. ACL policies are tailnet-wide and live on Tailscale's control plane. You change them via the **Tailscale REST API**.

The playbook does a read-modify-write as shown above:

```yaml
- name: Fetch current Tailscale ACL
  uri:
    url: "{{ tailscale_api }}/tailnet/{{ tailscale_tailnet }}/acl"
    method: GET
    headers:
      Authorization: "Bearer {{ tailscale_api_key }}"
    return_content: true
  register: current_acl

- name: Build new ACL (prepend ios-agent rule, preserve existing)
  set_fact:
    new_acl: >-
      {{
        acl_doc | combine({
          'acls': (
            [{
              'action': 'accept',
              'src': [mac_mini_tailscale_ip],
              'dst': [
                cylon_tailscale_ip + ':30881',
                cylon_tailscale_ip + ':30500'
              ]
            }] + (acl_doc.acls | default([]))
          )
        })
      }}

- name: Push updated ACL
  uri:
    url: "{{ tailscale_api }}/tailnet/{{ tailscale_tailnet }}/acl"
    method: POST
    headers:
      Authorization: "Bearer {{ tailscale_api_key }}"
      Content-Type: "application/json"
    body: "{{ new_acl | to_json }}"
    body_format: json
    status_code: 200
```

The Tailscale daemon polls the control plane periodically. When it picks up the change, it regenerates `ts-input` automatically — replacing the blanket `DROP` with a targeted `ACCEPT` for `100.102.68.75 → :30881,:30500`. No iptables touching needed.

**Generating the API key:** Go to `tailscale.com/admin/settings/keys` → Generate auth key → scope = **Read/Write ACLs**. Pass it at runtime:

```bash
ansible-playbook setup-tailscale-acl.yml \
  --extra-vars "tailscale_api_key=tskey-api-..."
```

### Part 2: Advertise the LAN Subnet — Eliminating the DERP Relay

Rather than wrestling with IPv4/IPv6 NAT traversal (which would require router port forwarding), we used **Tailscale subnet routing**:

```yaml
- name: Advertise LAN subnet from cylon
  command: tailscale set --advertise-routes=192.168.1.0/24

- name: Accept routes on Mac Mini
  delegate_to: mm1
  command: tailscale set --accept-routes=true
```

What happens:

1. Cylon tells the tailnet: *"I can route traffic for `192.168.1.0/24`"*
2. The Tailscale control plane propagates this to all peers
3. The Mac Mini installs a kernel route: `192.168.1.0/24 via cylon` through its Tailscale interface
4. When the Jenkins agent on the Mac Mini connects to `192.168.1.10:30881`, that traffic goes through the Tailscale overlay — but since both devices are on the same physical LAN, the path stays entirely local

Result: all build data travels LAN → LAN. No DERP relay involved. Only Tailscale's control-plane keepalives (~100 bytes every 25 seconds) use the overlay infrastructure.

**Verification:**

```
Before: Mac Mini relay: blr
After:  Mac Mini relay: none — direct ✓
```

### Part 3: Approve the Advertised Route via API

Advertising a subnet from cylon is not enough — a tailnet admin must approve it before peers accept it. We automate this approval so the playbook is fully hands-off:

```yaml
- name: Approve advertised routes on cylon
  uri:
    url: "{{ tailscale_api }}/device/{{ cylon_node_id }}/routes"
    method: POST
    headers:
      Authorization: "Bearer {{ tailscale_api_key }}"
      Content-Type: "application/json"
    body:
      routes:
        - "192.168.1.0/24"
    body_format: json
    status_code: 200
```

---

## Why Not Just Edit iptables?

The `ts-input` chain is **owned by the Tailscale daemon**. It regenerates the chain on every ACL poll cycle (~5 minutes). Any rule you manually insert gets silently overwritten. You'd be playing whack-a-mole with a process you don't control.

The right fix is at the layer that owns the policy: the Tailscale control plane. Once the ACL is updated there, the daemon generates correct iptables rules automatically.

---

## Why the Tailscale CLI Can't Update ACLs

```
tailscale set     ← local node preferences only
tailscale up      ← local node start / config
tailscale status  ← query local state
```

None of these touch ACL policy because ACLs are **tailnet-wide** — they govern all nodes, not just the one you're logged into. The API is the correct interface, requiring a tailnet-admin-scoped key. There's no `tailscale acl edit` command by design, for the same reason `kubectl` can't do cluster-admin operations without a properly scoped kubeconfig.

---

## The Full Playbook Command

```bash
# One-time: generate API key at tailscale.com/admin/settings/keys
# Scope: Read/Write ACLs

ansible-playbook \
  -i ansible/inventory.ini \
  ansible/setup-tailscale-acl.yml \
  --extra-vars "tailscale_api_key=tskey-api-YOUR_KEY"
```

After completion:

```
TASK [Report Mac Mini relay status]
ok: [localhost] => {
    "msg": "Mac Mini relay: none — direct. Direct connection established — no DERP bandwidth used."
}
```

The iOS build agent connects. Builds run on LAN bandwidth. Zero relay overhead.

---

## When the Playbook Ran — Four More Blocking Layers

Running the playbook against a real tailnet surfaced four additional problems that theory never caught. Each one had to be fixed before the agent could connect.

### A. Tailscale API Execution Bugs

**Bug 1 — Tailnet identifier returning 404.** The playbook used the literal tailnet name (`aditya.dk.rock@`) in the URL:

```
GET /api/v2/tailnet/aditya.dk.rock@/acl  →  404 Not Found
```

The Tailscale API accepts `-` as a special alias meaning "the tailnet of the authenticated token". Switch to that:

```yaml
tailscale_tailnet: "-"
```

This is documented behaviour, but easy to miss when copying the tailnet name from the admin console.

**Bug 2 — `comment` field rejected with 400.** The ACL rule included a `comment` key:

```
POST /api/v2/tailnet/-/acl  →  400 Bad Request
json: unknown field "comment"
```

The Tailscale REST API accepts strict JSON. HuJSON extensions (comments, trailing commas) are allowed in the admin console editor, but the API rejects them. The `comment` key was already removed from the playbook snippet above. Any rule objects in the `acls` array must contain only `action`, `src`, and `dst`.

**Bug 3 — `tailscale set` requires sudo.** The tasks that advertise and accept routes failed silently because the `tailscale` daemon socket is root-owned:

```yaml
# Before (broken):
- name: Advertise LAN subnet from cylon
  command: tailscale set --advertise-routes=192.168.1.0/24

# After (correct):
- name: Advertise LAN subnet from cylon
  command: tailscale set --advertise-routes=192.168.1.0/24
  become: true
```

Add `become: true` to both the advertise-routes and accept-routes tasks.

**Bug 4 — `tailscale` not in SSH PATH on macOS.** When Ansible SSHes into the Mac Mini, the shell PATH is a minimal set that does not include `/usr/local/bin`. The delegate_to task for `--accept-routes` fails with `command not found`. Fix: use the full binary path:

```yaml
- name: Accept routes on Mac Mini
  delegate_to: mm1
  command: /usr/local/bin/tailscale set --accept-routes=true
  become: true
```

---

### B. Kubernetes NetworkPolicy Blocking NodePort Traffic

After fixing all four API bugs, Tailscale reported a direct connection and `nc` confirmed the NodePorts were open at the kernel level — the `ts-input` nftables chain had `iifname "tailscale0" accept`, so decrypted Tailscale packets were being let through. Yet the Mac Mini still could not reach Jenkins.

The actual block was the Kubernetes NetworkPolicy layer.

The `utilities` namespace has a `default-deny-all` policy. The `allow-jenkins-ci-internal` policy only permits pod-to-pod traffic matched by specific `app=` label selectors. When the Mac Mini hits `<node-ip>:30881`, kube-proxy DNATs the packet to the Jenkins pod IP. After DNAT, the Calico CNI enforces NetworkPolicy at the pod's veth interface. The source IP (after SNAT) matches no allowed pod selector — Calico drops it silently.

The fix is an additional ingress rule that explicitly allows external sources to reach the Jenkins pod on its internal ports (8080 HTTP, 50000 JNLP):

```yaml
# Append this rule to the allow-jenkins-ci-internal NetworkPolicy
# Allow external access to Jenkins controllers via NodePort
- ports:
  - port: 8080
    protocol: TCP
  - port: 50000
    protocol: TCP
  from:
  - ipBlock:
      cidr: 0.0.0.0/0
```

Apply it:

```bash
kubectl apply -f allow-jenkins-ci-internal.yaml -n utilities
```

No pod restart required. The NetworkPolicy change takes effect immediately via the Calico controller.

**Why port 8080 and 50000, not 30881 and 30500?** NetworkPolicy operates at the pod interface, after kube-proxy has already DNAT-ed the NodePort to the pod's port. You always specify the pod's internal port in the policy, never the NodePort.

---

### C. JNLP Port Mismatch: Internal Port vs NodePort

With the NetworkPolicy patched, `nc -zv <node-ip> 30881` and `nc -zv <node-ip> 30500` both succeeded from the Mac Mini. The agent jar was downloaded and launched:

```bash
java -jar agent.jar \
  -url http://<node-ip>:30881 \
  -secret <agent-secret> \
  -name ios-agent \
  -workDir /Users/builder/jenkins-agent
```

Error:

```
http://<node-ip>:30881/ provided port:50000 is not reachable
```

This is a fundamental Jenkins JNLP behaviour: when the agent jar connects to the HTTP endpoint, Jenkins reads its own configured JNLP port (50000, the internal pod port) and advertises it in the response. The agent then attempts a direct TCP connection to `<node-ip>:50000` — not the NodePort 30500, just the raw internal port — which is not open on the host.

Fix: pass `-tunnel` to override the advertised address:

```bash
java -jar agent.jar \
  -url http://<node-ip>:30881 \
  -secret <agent-secret> \
  -name ios-agent \
  -workDir /Users/builder/jenkins-agent \
  -tunnel <node-ip>:30500
```

The `-tunnel` flag bypasses the port Jenkins advertises and connects directly to the specified address. This flag must also appear in:

- The launchd plist (so the agent reconnects correctly after reboot):

```xml
<string>-tunnel</string>
<string><node-ip>:30500</string>
```

- The Ansible handler that writes or templates the plist, so it is always rendered with the correct value.

---

### D. Disk Space: Jenkins Offline Trigger

After the agent connected — Jenkins logs confirmed `Accepted JNLP4-connect connection` — Jenkins immediately put the node offline:

```
Making ios-agent offline temporarily due to the lack of disk space
```

The Mac Mini disk was at 98% capacity (256 MB free). Jenkins' default disk-space threshold is approximately 1 GB. The agent was accepted, evaluated, and immediately suspended before running a single build step.

Fix:

```bash
brew cleanup
```

This removes old portable-ruby versions, stale formula downloads, and cached bottle archives. In this case it freed over 6 GB. The agent reconnected automatically and stayed online.

This is safe to run at any time — `brew cleanup` only removes files that Homebrew tracks as no longer needed (old versions, cached downloads). It does not touch user data or installed formula symlinks.

Consider adding a disk-space check to the Ansible playbook as a pre-flight assertion:

```yaml
- name: Assert sufficient free disk space on Mac Mini
  delegate_to: mm1
  shell: df -k / | awk 'NR==2{print $4}'
  register: free_kb
  failed_when: free_kb.stdout | int < 2097152   # 2 GB in KB
```

This catches the problem at provisioning time rather than after the agent connects.

---

## Key Takeaways

| Problem | ❌ Wrong Fix | ✅ Right Fix |
|---|---|---|
| Tailscale blocking peer traffic | Edit `ts-input` in iptables (gets overwritten) | Update ACL via Tailscale API |
| DERP relay consuming bandwidth | Port-forward for UDP hole-punch | Advertise LAN subnet route |
| Ports unreachable from Mac Mini | Open firewall rules manually | ACL + subnet route together |
| Repeatable and auditable | Ad-hoc shell commands | Ansible playbook in the repo |
| Tailscale API 404 on tailnet name | Use literal org name in URL | Use `-` as the tailnet alias |
| API 400 on `comment` field | Leave comment, ignore the error | Remove `comment` — strict JSON only |
| `tailscale set` silently fails | Run without sudo, check no output | Add `become: true`; use full binary path on macOS |
| Kubernetes NetworkPolicy drops NodePort traffic | Punch holes in the node firewall | Add `ipBlock: 0.0.0.0/0` ingress rule to the NetworkPolicy |
| Jenkins advertises internal JNLP port, agent can't connect | Open port 50000 on the host | Pass `-tunnel <node-ip>:<nodeport>` to the agent jar |
| Jenkins marks node offline immediately | Ignore the message, restart Jenkins | Run `brew cleanup`; assert free disk in the playbook |

**The principle:** The Tailscale control plane is the single source of truth for network policy. Work with it, not around it. The API gives you everything you need — ACLs, device routes, node management — with the same idempotency guarantees you get from Kubernetes manifests or Ansible playbooks.

---

## Part 2 — Running the Pipelines (And Everything That Broke)

With the agent connected, we had three iOS pipelines and one Android pipeline to run. Seven more problems surfaced.

### Problem 8: SMTP — `namshi/smtp` Can't Relay

The original SMTP relay used `namshi/smtp` (Exim4). Exim4 has a `dc_smarthost` config variable, but the Docker image never populated it correctly for SASL auth. Emails silently dropped.

**Fix:** Switch to `boky/postfix`. It reads relay config from env vars directly:

```yaml
- name: RELAYHOST
  value: "[live.smtp.mailtrap.io]:587"
- name: RELAYHOST_USERNAME
  valueFrom:
    secretKeyRef:
      name: mailtrap-smtp-creds
      key: username   # value: "api"
- name: RELAYHOST_PASSWORD
  ...
- name: POSTFIX_sender_canonical_maps
  value: "static:hello@demomailtrap.co"
```

Also: `ALLOW_EMPTY_SENDER_DOMAINS=1` is required — `boky/postfix` refuses to start without it when no sender domain is explicitly configured.

### Problem 9: Mailtrap `535 Authentication Failed`

We had the wrong username. Mailtrap's live SMTP uses `api` as the username (not `apismtp@mailtrap.io`). The correct curl to test:

```bash
curl --url "smtps://live.smtp.mailtrap.io:465" --ssl-reqd \
  --mail-from "hello@demomailtrap.co" \
  --mail-rcpt "reject@hellodk.io" \
  --user "api:YOUR_TOKEN_HERE" -T message.txt
```

### Problem 10: Jenkins Tries SSL on Port 25

Jenkins's `emailext` was defaulting to SSL even though Postfix listens on plain port 25. Error: `NOQUEUE: lost connection after CONNECT`.

**Fix in `03-smtp.groovy`:**
```groovy
desc.setUseSsl(false)   // ← this line is critical
desc.setSmtpHost("smtp.utilities.svc.cluster.local")
desc.setSmtpPort("25")
```

The call `setUseSsl(false)` wasn't in the original init script. It's now explicit.

### Problem 11: Email Template Parse Error — `$1` in SimpleTemplateEngine

Jenkins's `emailext` plugin processes `.groovy` email templates through Groovy's `SimpleTemplateEngine`. This is NOT a Groovy script — it's a template. The entire file is scanned for `${...}` expressions, including inside single-quoted strings.

The original templates used regex replacements like `replaceAll(/.../,' $1 ')`. Even inside single quotes, `$1` is scanned and fails: _"illegal string body character after dollar sign"_.

**Fix:** Use closure-based regex replacements. Closures receive the capture groups as arguments — no `$1` needed:

```groovy
md = md.replaceAll(/(?m)^# (.+)/) { m, g -> "<h1>${g}</h1>" }
//                                   ↑ closure — not a string
```

Also: the entire code block must be wrapped in `<% %>`. HTML goes directly in the template body, not inside a `return """..."""`.

### Problem 12: `No such property: workspace` in Failure Email

`build.workspace` doesn't exist on `WorkflowRun` (Pipeline jobs). It's a `FreeStyleBuild` concept.

Also: `deleteDir()` in the `cleanup` post condition wipes the workspace before the email renders, so even if `workspace` existed, the file would be gone.

**Fix:** Archive `llm-analysis.md` as a Jenkins artifact, then read from the build's archive directory:

In the Jenkinsfile:
```groovy
archiveArtifacts artifacts: 'llm-analysis.md', allowEmptyArchive: true
```

In `failure-email.groovy`:
```groovy
def archiveDir = new File(build.getRootDir(), "archive")
def f = new File(archiveDir, "llm-analysis.md")
if (f.exists()) {
    def md = f.text
    // ... render md to HTML
}
```

### Problem 13: iOS Pipelines Jump to Post Actions Immediately

All three iOS jobs failed in under 2 seconds with no stages running. Error in cleanup post: "Attempted to execute a step that requires a node context while `agent none` was specified."

This was diagnosed over multiple angles:
- Sandbox=true → silent failure at pipeline initialization
- Sandbox=false → `UnapprovedUsageException` (script needs approval)
- The android-build job worked fine — it uses `CpsScmFlowDefinition` (reads Jenkinsfile from git)
- The iOS jobs had been replaced with `CpsFlowDefinition` (inline script) — which requires Script Security approval

**Root cause:** Inline pipeline scripts need explicit approval in Jenkins Script Security. SCM-based pipelines (reading from a git repo) don't have this restriction.

**Fix:** Switch iOS jobs to `CpsScmFlowDefinition` pointing to `file:///home/dk/Documents/git/testing-grounds` (accessible from the Jenkins controller pod as a mounted volume).

### Problem 14: `Declarative: Checkout SCM` Fails on Mac Mini

Even after switching to SCM-based configs, builds failed at the first stage: the implicit `Declarative: Checkout SCM`. Declarative Pipeline automatically checks out the SCM at build start — but this checkout runs on the agent (Mac Mini), which can't access `file:///home/dk/Documents/git/testing-grounds` (that path is on the k0s node, not on Mac Mini).

**Fix:** Add `options { skipDefaultCheckout(true) }` to all iOS Jenkinsfiles. The pipeline then only checks out the app repo explicitly (FoodTruck, mattermost-mobile), which is on the Mac Mini's local filesystem.

```groovy
pipeline {
    agent { label 'ios-agent' }
    options { skipDefaultCheckout(true) }   // ← this line
    ...
    stages {
        stage('Checkout') {
            steps {
                git url: 'file:///Users/dk/jenkins-agent/git/FoodTruck', branch: 'main'
```

### Problem 15: `LANG` Not Set — CocoaPods Unicode Error

`pod install` crashed with:
```
Unicode Normalization not appropriate for ASCII-8BIT (Encoding::CompatibilityError)
```

CocoaPods uses Ruby's `unicode_normalize` which requires UTF-8. The launchd plist didn't set `LANG`.

**Fix in launchd plist:**
```xml
<key>LANG</key>
<string>en_US.UTF-8</string>
```

And in the Jenkinsfile step as a belt-and-suspenders measure:
```groovy
sh 'export LANG=en_US.UTF-8; cd ios && pod install --repo-update'
```

---

## Final State

Three iOS pipelines succeeding on the Mac Mini M2:
- `ios-swift-xcodebuild` (FoodTruck, xcodebuild directly) — 29s
- `ios-fastlane` (FoodTruck, Fastlane) — 46s
- `ios-react-native` (mattermost-mobile, npm ci + pod install) — ~8 min

One Android pipeline succeeding on the k8s agent pod with LLM-assisted failure analysis.

All emails delivered to `reject@hellodk.io` via Mailtrap live SMTP.
