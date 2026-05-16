# iOS Pipelines on Jenkins

Three pipeline options that build iOS apps on Mac Mini agents registered as static JNLP agents.

| Agent | Label | Hardware | Provisioned by |
|---|---|---|---|
| mm1 | `ios-agent` | Mac Mini M2 | Ansible (`ansible/setup-ios-agent.yml`) |
| mobileapp-m3 | `mobileapp-m3` | Mac Mini M3 16 GB | Manual (see `docs/mac-mini-setup.md`) |

---

## Mac Mini Agent Setup

The Mac Mini connects to Jenkins via Tailscale. The agent is managed by launchd and survives reboots.

**Tailscale IPs:**
- k0s node (Jenkins): `100.89.50.27`
- Mac Mini: `100.102.68.75`

**Jenkins NodePorts (on k0s node):**
- HTTP: `30881`
- JNLP: `30500`

**Provisioning:** `ansible/setup-ios-agent.yml` installs and configures the agent. Run it from the k0s node:

```bash
cd ansible
ansible-playbook -i inventory.ini setup-ios-agent.yml
```

**Required tools on Mac Mini:**
- Xcode (tested with 26.4.1)
- Java 21 (for the JNLP agent jar)
- Homebrew
- nvm + Node.js LTS (for React Native)
- rbenv + Ruby 3.2.2 (for CocoaPods and Fastlane)
- CocoaPods (`gem install cocoapods`)
- SwiftLint (`brew install swiftlint`)

**Agent launchd plist:** `~/Library/LaunchAgents/com.jenkins.ios-agent.plist`

Key environment variables set in the plist:
```
PATH     = ~/.nvm/versions/node/v24.15.0/bin:~/.rbenv/shims:~/.rbenv/bin:/opt/homebrew/bin:...
LANG     = en_US.UTF-8   ← required by CocoaPods (unicode normalization)
NVM_DIR  = ~/.nvm
RBENV_ROOT = ~/.rbenv
```

---

## How the Jenkinsfile Is Loaded

**All three iOS jobs use `CpsScmFlowDefinition`** — the Jenkinsfile is read from git, not stored inline.

```
Jenkins controller (k8s pod) → reads Jenkinsfile from file:///home/dk/Documents/git/pulse
↓
Pipeline script loaded
↓
Pipeline runs on ios-agent (Mac Mini)
```

**Why not inline scripts?** Inline `CpsFlowDefinition` requires explicit script approval in Jenkins Script Security. SCM-based pipelines don't have this restriction.

**Why `skipDefaultCheckout(true)`?** All three Jenkinsfiles declare `options { skipDefaultCheckout(true) }`.

Without it, Declarative Pipeline automatically checks out `file:///home/dk/Documents/git/pulse` at the start of every build — on the ios-agent. But that path only exists in the k8s pod (mounted volume). The Mac Mini can't access it. `skipDefaultCheckout(true)` skips this implicit checkout; the pipeline then does its own explicit checkout of the app repo (`FoodTruck`, `mattermost-mobile`).

**The path `file:///home/dk/Documents/git/pulse`:**
- Accessible from the Jenkins controller pod (mounted as a volume)
- NOT accessible from the Mac Mini (Linux path, different machine)

The Mac Mini also has a clone of the repo at `~/Documents/git/pulse` for reference, but it's not used in the build.

---

## Recreating Job Configs

If the Jenkins jobs are deleted, recreate them from the saved XMLs:

```bash
NODE_IP=100.89.50.27
JENKINS=http://${NODE_IP}:30881

# Get CSRF crumb
CRUMB=$(curl --user admin:admin123 -s "${JENKINS}/crumbIssuer/api/json" | \
  python3 -c "import sys,json;d=json.load(sys.stdin);print(d['crumb'])")

for job in ios-swift-xcodebuild ios-fastlane ios-react-native; do
  curl --user admin:admin123 -H "Jenkins-Crumb: ${CRUMB}" \
    -X POST "${JENKINS}/createItem?name=${job}" \
    --data-binary @"job-configs/${job}.xml" \
    -H "Content-Type: application/xml"
done
```

---

## Pipeline Options

### Option 1 — Swift / xcodebuild (`ios-swift-xcodebuild`)

**App:** FoodTruck (Swift Package Manager, no CocoaPods)  
**Jenkinsfile:** `option-1-swift/Jenkinsfile`

**Stages:**
```
Checkout (FoodTruck) → Validate → Build Archive → Tests (parallel: JS + Native)
  → SAST (SwiftLint) → Upload (Nexus + JFrog)
```

Produces a `.xcarchive.zip` artifact (no IPA — code signing not configured).

### Option 2 — Fastlane (`ios-fastlane`)

**App:** FoodTruck  
**Jenkinsfile:** `option-2-fastlane/Jenkinsfile`

**Stages:**
```
Checkout (FoodTruck) → Bundle Install → Resolve Dependencies → Test
  → Build → SAST → Upload
```

Each stage calls `bundle exec fastlane <lane>`. Fastlane lanes are defined in `FoodTruck/fastlane/Fastfile` (in the FoodTruck repo, not this repo).

**FoodTruck Fastfile lanes:** `resolve_deps`, `test`, `build`, `sast`, `upload_nexus`, `upload_jfrog`

Note: FoodTruck has no test target configured in its scheme. The `test` lane catches the error and continues.

### Option 3 — React Native (`ios-react-native`)

**App:** mattermost-mobile (React Native)  
**Jenkinsfile:** `option-3-react-native/Jenkinsfile`

**Stages:**
```
Checkout (mattermost-mobile) → Validate → JS Dependencies (npm ci)
  → iOS Native Dependencies (pod install) → Build Archive
  → Tests (parallel: Jest + xcodebuild)
  → SAST (parallel: ESLint + SwiftLint) → Upload
```

**Dependency order matters:** `npm ci` must run before `pod install`. The React Native podspecs land in `node_modules/` first; CocoaPods reads from there.

`pod install` requires `export LANG=en_US.UTF-8` — set in the step and in the launchd plist.

---

## Troubleshooting

### Build jumps straight to Post Actions (no stages run)

**Cause:** If `skipDefaultCheckout(true)` is missing, the implicit `Declarative: Checkout SCM` stage tries to git-fetch `file:///home/dk/Documents/git/pulse` on the Mac Mini agent. That path only exists on the Linux k8s host — not on the Mac Mini — so it fails and the pipeline skips all stages.

**Fix:** Ensure all three Jenkinsfiles have `options { skipDefaultCheckout(true) }`.

### `The server rejected the connection: None of the protocols were accepted`

The JNLP agent is already connected (another instance). Only one instance can connect per node name.

```bash
# Check if already running
ssh dk@100.102.68.75 "launchctl list | grep jenkins"

# Disconnect stale connection via Jenkins API
curl --user admin:admin123 -X POST \
  "http://100.89.50.27:30881/computer/ios-agent/doDisconnect?offlineMessage=reset"
```

### `Unicode Normalization not appropriate for ASCII-8BIT`

CocoaPods requires UTF-8. The LANG env var is missing. Ensure `export LANG=en_US.UTF-8` is set in the `sh` step before `pod install`, and that `LANG=en_US.UTF-8` is in the launchd plist.

### ios-agent shows online but builds fail immediately

Check if the launchd service is actually connected:
```bash
ssh dk@100.102.68.75 "tail -5 ~/jenkins-agent/agent-error.log"
```
The last line should say `INFO: Connected`. If not, reload the service:
```bash
ssh dk@100.102.68.75 "launchctl unload ~/Library/LaunchAgents/com.jenkins.ios-agent.plist && \
  launchctl load ~/Library/LaunchAgents/com.jenkins.ios-agent.plist"
```

### Disk pressure taint on Mac Mini agent

Mac Mini disk usage (check with `df -h /`). After `brew cleanup`, `xcrun simctl delete unavailable`, or clearing DerivedData:
```bash
ssh dk@100.102.68.75 "rm -rf ~/Library/Developer/Xcode/DerivedData/*"
```
