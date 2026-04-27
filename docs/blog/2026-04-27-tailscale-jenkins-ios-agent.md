# Connecting a macOS Jenkins Agent Through Tailscale — ACLs, No DERP, and Ansible

When you run Jenkins on Kubernetes and want to add a Mac Mini as a build agent for iOS pipelines, you quickly hit a wall: the Mac Mini can't reach the Jenkins NodePort. In our setup, both machines are on the same LAN, but AP isolation on the router and Tailscale's default ACL policy both block the connection. This post covers how we fixed it cleanly — no iptables hacking, no manual web console clicking, everything through Ansible and the Tailscale API.

---

## The Setup

```
┌────────────────────────────────────────────┐
│  cylon (192.168.1.10 / 100.89.50.27)       │
│  k0s Kubernetes — utilities namespace      │
│    Jenkins C controller  NodePort :30881   │
│    Jenkins JNLP          NodePort :30500   │
└────────────────────────────────────────────┘
              │  same LAN + Tailscale
              │
┌─────────────────────────────────────────────┐
│  deepaks-mac-mini (192.168.1.64 /           │
│                    100.102.68.75)           │
│  macOS 15.7.3 Sequoia — iOS build agent    │
└─────────────────────────────────────────────┘
```

Jenkins runs inside k0s. Its ports are exposed as NodePorts. The Mac Mini needs to reach two of them:

- **30881** — Jenkins HTTP API (for downloading `agent.jar` and agent registration)
- **30500** — JNLP raw TCP (the channel that carries build logs and artifacts)

---

## Why the Mac Mini Couldn't Connect

Three separate problems stacked up:

### 1. AP Isolation on the Router

Many home and office routers have "client isolation" or "AP isolation" enabled for the wireless network. Devices can reach the gateway but not each other. The Ansible controller (cylon) could SSH *to* the Mac Mini fine (cylon initiates the connection), but the Mac Mini couldn't initiate connections *to* cylon's ports.

### 2. Tailscale's Default ACL — the `ts-input` DROP Rule

Tailscale enforces ACL policies via iptables. When you have a custom ACL that doesn't explicitly allow peer-to-peer traffic, the Tailscale daemon inserts a blanket DROP rule into the `ts-input` chain:

```
Chain ts-input
1  ACCEPT  --  100.89.50.27   0.0.0.0/0        ← self
2  RETURN  --  100.115.92.0/23 0.0.0.0/0       ← Tailscale internal
3  DROP    --  100.64.0.0/10  0.0.0.0/0        ← all other Tailscale peers
4  ACCEPT  --  0.0.0.0/0      0.0.0.0/0
5  ACCEPT  --  0.0.0.0/0      udp dpt:41641
```

Rule 3 drops everything from the Tailscale CGNAT range (`100.64.0.0/10`). The Mac Mini's Tailscale IP `100.102.68.75` falls squarely in that range. Result: Tailscale itself was blocking the Mac Mini even when both devices were on the same physical LAN.

### 3. DERP Relay Instead of Direct Connection

Even for traffic that did get through, the Mac Mini was routing via the Bangalore DERP relay (`blr`) instead of talking directly to cylon. The relay was being used because:

- The Mac Mini connected from an IPv6 address (`[2402:e280:...]`)
- Cylon had no matching publicly reachable IPv6 endpoint
- UDP hole-punch failed → Tailscale fell back to `blr`

DERP relays aren't a problem for tiny control messages, but for a CI agent that streams build logs and transfers artifacts, routing everything through a cloud relay adds unnecessary latency and burns ISP bandwidth.

---

## The Fix — Three Parts, One Ansible Playbook

The entire fix is expressed as an Ansible playbook (`setup-tailscale-acl.yml`) that runs on `localhost` (cylon). No SSH needed to the Mac Mini for this step.

### Part 1: Update the Tailscale ACL via API

Tailscale's CLI (`tailscale` binary) only manages the **local node** — routes, exit nodes, DNS preferences. ACL policies are tailnet-wide and live on Tailscale's control plane. You change them via the **Tailscale API**, not the CLI.

```
GET  https://api.tailscale.com/api/v2/tailnet/{tailnet}/acl
     → returns the current HuJSON ACL document

POST https://api.tailscale.com/api/v2/tailnet/{tailnet}/acl
     → replaces it with the new document
```

The playbook does a read-modify-write:

```yaml
- name: Fetch current Tailscale ACL
  uri:
    url: "{{ tailscale_api }}/tailnet/{{ tailscale_tailnet }}/acl"
    method: GET
    headers:
      Authorization: "Bearer {{ tailscale_api_key }}"
    return_content: true
    status_code: 200
  register: current_acl

- name: Build new ACL (prepend ios-agent rule)
  set_fact:
    new_acl: >-
      {{
        acl_doc | combine({
          'acls': (
            [
              {
                'action': 'accept',
                'src': [mac_mini_tailscale_ip],
                'dst': [
                  cylon_tailscale_ip + ':30881',
                  cylon_tailscale_ip + ':30500'
                ],
                'comment': 'ios-agent Jenkins HTTP + JNLP'
              }
            ] + (acl_doc.acls | default([]))
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

The Tailscale daemon on every node polls the control plane periodically and picks up the change. The `ts-input` DROP rule gets automatically replaced with a targeted ACCEPT for `100.102.68.75 → 30881,30500`. No iptables touching needed.

The API key needs **Read/Write ACLs** scope. Generate it at `tailscale.com/admin/settings/keys`. Pass it at runtime:

```bash
ansible-playbook setup-tailscale-acl.yml \
  --extra-vars "tailscale_api_key=tskey-api-..."
```

### Part 2: Advertise the LAN Subnet — Eliminating the DERP Relay

Rather than trying to force a direct IPv4/IPv6 tunnel between the two nodes (which requires port forwarding or STUN/TURN infrastructure), we used **Tailscale subnet routing**:

```yaml
- name: Advertise LAN subnet from cylon
  command: tailscale set --advertise-routes=192.168.1.0/24

- name: Accept routes on Mac Mini
  delegate_to: mm1
  command: tailscale set --accept-routes=true
```

What this does:

1. Cylon announces to the tailnet: *"I can route `192.168.1.0/24`"*
2. The Tailscale control plane propagates this to all peers
3. The Mac Mini installs a kernel route: `192.168.1.0/24 via cylon` through the Tailscale interface
4. When the Mac Mini's Jenkins agent connects to `192.168.1.10:30881`, it goes through the Tailscale overlay — but since both devices are physically on the same LAN, Tailscale's routing engine keeps the path local

The result: build traffic travels LAN → LAN without touching any Tailscale DERP server. Only the initial keepalive handshake (~100 bytes every 25 seconds) uses the Tailscale overlay infrastructure. Zero relay bandwidth for actual CI data.

We verify the change worked:

```yaml
- name: Check Mac Mini relay status
  vars:
    peer_info: >-
      {{
        (ts_status.stdout | from_json).Peer.values()
        | selectattr('TailscaleIPs', 'contains', mac_mini_tailscale_ip)
        | list | first | default({})
      }}
  debug:
    msg: "Mac Mini relay: {{ peer_info.Relay | default('none — direct') }}"
```

Before: `Mac Mini relay: blr`  
After: `Mac Mini relay: none — direct`

### Part 3: Approve the Subnet Route via API

Advertising a route is not enough — a Tailscale admin must approve it before peers accept it. We automate this approval:

```yaml
- name: Approve advertised routes on cylon
  uri:
    url: "{{ tailscale_api }}/v2/device/{{ cylon_node_id }}/routes"
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

The `ts-input` chain is owned and managed by the Tailscale daemon. Any manual rule you insert into it will be silently overwritten the next time the daemon regenerates its policy (which happens on every ACL poll cycle, roughly every 5 minutes). You'd be playing whack-a-mole with a process you don't control.

The right fix is at the layer that owns the policy: the Tailscale control plane. Once the ACL is updated there, the daemon generates correct iptables rules on its own.

---

## Why the Tailscale CLI Can't Update ACLs

```
tailscale set        ← local node preferences only
tailscale up         ← local node start/config
tailscale status     ← query local state
```

None of these can touch ACL policy because ACLs are **tailnet-wide** — they govern all nodes, not just the one you're logged into. Changing them requires authenticating as a tailnet admin, which is what the API key provides. The API is the correct interface; there's no `tailscale acl edit` command by design, just as `kubectl` can't authenticate as cluster-admin without a properly scoped kubeconfig.

---

## The Full Ansible Command

```bash
# One-time: generate API key at tailscale.com/admin/settings/keys
# Scope: Read/Write ACLs, no expiry (or set a rotation schedule)

ansible-playbook \
  -i ansible/inventory.ini \
  ansible/setup-tailscale-acl.yml \
  --extra-vars "tailscale_api_key=tskey-api-YOUR_KEY"
```

After it completes:

```
TASK [Report Mac Mini relay status]
ok: [localhost] => {
    "msg": "Mac Mini relay: none — direct. Direct connection established — no DERP bandwidth used."
}
```

The iOS build agent connects. Builds run. No relay bandwidth consumed.

---

## Key Takeaways

| Problem | Wrong fix | Right fix |
|---|---|---|
| Tailscale blocking peer traffic | Edit `ts-input` in iptables | Update ACL via Tailscale API |
| DERP relay consuming bandwidth | Force UDP hole-punch via port forwarding | Advertise LAN subnet route |
| Ports unreachable from Mac Mini | Open firewall rules manually | ACL + subnet route together |
| Repeatable / auditable | Ad-hoc shell commands | Ansible playbook in the repo |

The Tailscale control plane is the single source of truth for network policy. Work with it, not around it.
