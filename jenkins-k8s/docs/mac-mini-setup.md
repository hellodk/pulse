# Mac Mini Setup Guide — Jenkins / Fastlane / iOS Builds

Complete setup guide for a Mac Mini (M-series Apple Silicon) as a Jenkins
build agent for iOS enterprise pipelines using Fastlane and xcodebuild.

Tested on: **macOS Tahoe 26, Mac Mini M3 16 GB RAM**

---

## Hardware & OS Requirements

| Item | Minimum | Recommended |
|---|---|---|
| Chip | Apple Silicon M1 | M3 or later |
| RAM | 8 GB | 16 GB |
| Storage | 256 GB | 512 GB |
| macOS | Ventura (13) | Tahoe (26) |
| Xcode | 15 | Latest stable |

---

## 1. Xcode & Command Line Tools

Install the full Xcode from the App Store, then confirm CLI tools are active:

```bash
xcode-select -p
# Expected: /Applications/Xcode.app/Contents/Developer
# If missing or showing CommandLineTools only:
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

Accept the Xcode licence:
```bash
sudo xcodebuild -license accept
```

---

## 2. Homebrew

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Apple Silicon — add Homebrew to PATH
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
source ~/.zprofile

brew --version
# Expected: Homebrew 5.x.x
```

---

## 3. OpenSSL (real OpenSSL, not macOS LibreSSL)

```bash
brew install openssl

openssl version
# Expected: OpenSSL 3.x.x  (NOT LibreSSL)
```

---

## 4. jq (required by llm-analysis.sh)

```bash
brew install jq
jq --version
# Expected: jq-1.7.x
```

---

## 5. Java 21 (Jenkins agent requirement)

```bash
brew install openjdk@21

# Add to PATH permanently (Apple Silicon path)
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zprofile
source ~/.zprofile

java -version
# Expected: openjdk version "21.0.x"
```

### Java binary path
```
/opt/homebrew/opt/openjdk@21/bin/java
```

---

## 6. Ruby 3.4.5 via rbenv

macOS ships with system Ruby (2.6.x) which must NOT be used for CI.
rbenv manages Ruby versions per shell/project.

```bash
# Install rbenv + ruby-build
brew install rbenv ruby-build

# Add rbenv to PATH and initialise shims
echo 'export PATH="$HOME/.rbenv/bin:$PATH"' >> ~/.zprofile
echo 'eval "$(rbenv init -)"' >> ~/.zprofile
source ~/.zprofile

# Install Ruby 3.4.5
rbenv install 3.4.5

# Set as global default
rbenv global 3.4.5
rbenv rehash

ruby --version
# Expected: ruby 3.4.5 (2025-xx-xx ...) [arm64-darwin25]
```

### Ruby paths
| Item | Path |
|---|---|
| Ruby binary | `~/.rbenv/versions/3.4.5/bin/ruby` |
| Gem home | `~/.rbenv/versions/3.4.5/lib/ruby/gems/3.4.0` |
| Gem binary dir | `~/.rbenv/versions/3.4.5/bin/` |
| rbenv shims | `~/.rbenv/shims/` |

Verify gem home:
```bash
gem env home
# Expected: /Users/dk/.rbenv/versions/3.4.5/lib/ruby/gems/3.4.0
```

---

## 7. CocoaPods

Must be installed **after** Ruby 3.4.5 is active.

```bash
gem install cocoapods

pod --version
# Expected: 1.x.x
```

### CocoaPods binary path
```
~/.rbenv/versions/3.4.5/bin/pod
# also available via shim: ~/.rbenv/shims/pod
```

Disable analytics (CI best practice):
```bash
export COCOAPODS_DISABLE_STATS=1
echo 'export COCOAPODS_DISABLE_STATS=1' >> ~/.zprofile
```

---

## 8. xcpretty (formats xcodebuild output)

```bash
gem install xcpretty

xcpretty --version
# Expected: 0.4.x
```

### xcpretty binary path
```
~/.rbenv/versions/3.4.5/bin/xcpretty
# also via shim: ~/.rbenv/shims/xcpretty
```

---

## 9. Node.js 20 via nvm

**Do not use Homebrew Node** — it conflicts with nvm and has caused issues
with pnpm and native modules. Use nvm exclusively.

```bash
# Install nvm
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash

# Add to ~/.zprofile (nvm installer may add to ~/.zshrc — move to zprofile)
echo 'export NVM_DIR="$HOME/.nvm"' >> ~/.zprofile
echo '[ -s "$NVM_DIR/nvm.sh" ] && source "$NVM_DIR/nvm.sh"' >> ~/.zprofile
source ~/.zprofile

# Install and pin Node 20 LTS
nvm install 20
nvm use 20
nvm alias default 20

node --version   # Expected: v20.x.x
npm --version    # Expected: 10.x.x

# Confirm nvm's node wins (not Homebrew)
which node
# Expected: /Users/dk/.nvm/versions/node/v20.x.x/bin/node
```

### Node paths
| Item | Path |
|---|---|
| Node binary | `~/.nvm/versions/node/v20.x.x/bin/node` |
| npm binary | `~/.nvm/versions/node/v20.x.x/bin/npm` |
| Global modules | `~/.nvm/versions/node/v20.x.x/lib/node_modules/` |

> **Note:** If Homebrew Node was previously installed, remove it first:
> `brew uninstall node`

---

## 10. Full ~/.zprofile Reference

After all installations, `~/.zprofile` should contain (in this order):

```bash
# Homebrew (Apple Silicon)
eval "$(/opt/homebrew/bin/brew shellenv)"

# Java 21
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"

# rbenv (Ruby 3.4.5)
export PATH="$HOME/.rbenv/bin:$PATH"
eval "$(rbenv init -)"

# nvm (Node 20)
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && source "$NVM_DIR/nvm.sh"

# CocoaPods
export COCOAPODS_DISABLE_STATS=1

# Locale (required by CocoaPods unicode normalisation)
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
```

---

## 11. Jenkins Agent Setup

### 11.1 Create agent in Jenkins UI

**Manage Jenkins → Nodes → New Node**

| Field | Value |
|---|---|
| Node name | `mobileapp-m3` |
| Type | Permanent Agent |
| Description | `Mac Mini M3 16GB — iOS codesign` |
| Executors | `2` |
| Remote root | `/Users/dk/jenkins-agent` |
| Labels | `mobileapp-m3 ios-agent` |
| Launch method | Launch agent by connecting to controller |

Save → copy the **secret token** shown on the node page.

### 11.2 Download the agent jar

```bash
mkdir -p /Users/dk/jenkins-agent
curl -sO http://<JENKINS_HOST>:30881/jnlpJars/agent.jar
cp agent.jar /Users/dk/jenkins-agent/agent.jar
```

### 11.3 Test connection (manual, one-time)

```bash
java -jar /Users/dk/jenkins-agent/agent.jar \
  -url http://<JENKINS_HOST>:30881/ \
  -secret <SECRET_TOKEN> \
  -name "mobileapp-m3" \
  -webSocket \
  -workDir "/Users/dk/jenkins-agent"
# Expected last line: INFO: Connected
```

### 11.4 Make permanent with launchd

Create the plist using Python (avoids XML heredoc corruption issues):

```bash
python3 - << 'EOF'
import plistlib
plist = {
    'Label': 'io.jenkins.agent',
    'ProgramArguments': [
        '/opt/homebrew/opt/openjdk@21/bin/java',
        '-jar', '/Users/dk/jenkins-agent/agent.jar',
        '-url', 'http://<JENKINS_HOST>:30881/',
        '-secret', '<SECRET_TOKEN>',
        '-name', 'mobileapp-m3',
        '-webSocket',
        '-workDir', '/Users/dk/jenkins-agent'
    ],
    'EnvironmentVariables': {
        'PATH': '/opt/homebrew/bin:/opt/homebrew/opt/openjdk@21/bin'
               ':/Users/dk/.rbenv/shims:/Users/dk/.rbenv/bin'
               ':/Users/dk/.nvm/versions/node/v20.19.1/bin'
               ':/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin',
        'LANG':    'en_US.UTF-8',
        'HOME':    '/Users/dk',
        'NVM_DIR': '/Users/dk/.nvm',
        'RBENV_ROOT': '/Users/dk/.rbenv'
    },
    'WorkingDirectory': '/Users/dk/jenkins-agent',
    'RunAtLoad': True,
    'KeepAlive': True,
    'StandardOutPath': '/Users/dk/jenkins-agent/agent.log',
    'StandardErrorPath': '/Users/dk/jenkins-agent/agent.log'
}
with open('/tmp/io.jenkins.agent.plist', 'wb') as f:
    plistlib.dump(plist, f)
print("Written to /tmp/io.jenkins.agent.plist")
EOF

sudo cp /tmp/io.jenkins.agent.plist /Library/LaunchDaemons/io.jenkins.agent.plist
sudo chown root:wheel /Library/LaunchDaemons/io.jenkins.agent.plist
sudo chmod 644 /Library/LaunchDaemons/io.jenkins.agent.plist

# Validate
plutil -lint /Library/LaunchDaemons/io.jenkins.agent.plist
# Expected: /Library/LaunchDaemons/io.jenkins.agent.plist: OK

# Load (macOS Ventura+ syntax)
sudo launchctl bootstrap system /Library/LaunchDaemons/io.jenkins.agent.plist
```

> **Note:** On macOS Tahoe, `launchctl bootstrap` may return
> `Bootstrap failed: 5: Input/output error` but still start the service.
> Confirm with: `ps -ef | grep java | grep agent`
> If PPID is 1 (launchd), the service is running correctly.

### 11.5 Verify agent is running

```bash
# Check process
ps -ef | grep agent.jar | grep -v grep

# Check log
tail -20 /Users/dk/jenkins-agent/agent.log
# Expected: INFO: Connected

# Remove agent (if needed)
sudo launchctl bootout system /Library/LaunchDaemons/io.jenkins.agent.plist
```

---

## 12. Dummy Signing Setup (pipeline testing without Apple account)

```bash
# Clone the repo (adjust URL to your Jenkins git URL or SSH)
git clone ssh://dk@<HOST>/home/dk/Documents/git/pulse ~/jenkins-k8s-repo

cd ~/jenkins-k8s-repo/jenkins-k8s/ios/option-4-enterprise
chmod +x generate-dummy-signing.sh

# Run interactive tool
./generate-dummy-signing.sh
# → Choose option 1 (Generate)
# → Confirm configuration
```

After generation, trust the CA in the System keychain:
```bash
sudo security add-trusted-cert -d -r trustRoot \
  -k /Library/Keychains/System.keychain \
  dummy-signing/ca.crt
```

> **macOS Tahoe note:** The `add-trusted-cert` command may fail with
> "authorization denied since no user interaction was possible."
> This is a Tahoe restriction. The pipeline still works because
> xcodebuild receives `OTHER_CODE_SIGN_FLAGS="--keychain ios-banknow-dummy.keychain"`
> which bypasses the system trust chain check.

Verify the identity is in the keychain:
```bash
security find-identity -p codesigning ios-banknow-dummy.keychain
# Expected: 1) XXXXXXXX "iPhone Distribution: YourBank Ltd (DUMTEAM01)"
```

---

## 13. Path Reference Summary

| Tool | Binary Path | Version |
|---|---|---|
| java | `/opt/homebrew/opt/openjdk@21/bin/java` | 21.0.x |
| ruby | `~/.rbenv/versions/3.4.5/bin/ruby` | 3.4.5 |
| gem | `~/.rbenv/versions/3.4.5/bin/gem` | bundled with Ruby |
| bundle | `~/.rbenv/versions/3.4.5/bin/bundle` | bundled with Ruby |
| pod | `~/.rbenv/shims/pod` | 1.x.x |
| xcpretty | `~/.rbenv/shims/xcpretty` | 0.4.x |
| node | `~/.nvm/versions/node/v20.x.x/bin/node` | 20.x.x |
| npm | `~/.nvm/versions/node/v20.x.x/bin/npm` | 10.x.x |
| brew | `/opt/homebrew/bin/brew` | 5.x.x |
| openssl | `/opt/homebrew/bin/openssl` | 3.x.x |
| jq | `/opt/homebrew/bin/jq` | 1.7.x |
| security | `/usr/bin/security` | system |
| codesign | `/usr/bin/codesign` | system |
| xcodebuild | `/usr/bin/xcodebuild` | from Xcode |

---

## 14. Verification Checklist

Run this on the Mac Mini to confirm everything is set up:

```bash
echo "=== Java ===" && java -version
echo "=== Ruby ===" && ruby --version
echo "=== Gem home ===" && gem env home
echo "=== CocoaPods ===" && pod --version
echo "=== xcpretty ===" && xcpretty --version
echo "=== Node ===" && node --version
echo "=== npm ===" && npm --version
echo "=== OpenSSL ===" && openssl version
echo "=== jq ===" && jq --version
echo "=== xcodebuild ===" && xcodebuild -version
echo "=== Jenkins agent ===" && ps -ef | grep agent.jar | grep -v grep | awk '{print "Running — PID "$2" PPID "$3}'
echo "=== Keychain ===" && security find-identity -p codesigning ios-banknow-dummy.keychain 2>/dev/null || echo "Dummy keychain not set up yet"
```

---

## 15. Troubleshooting

### `java: command not found` after brew install
```bash
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
source ~/.zprofile
```

### `ruby --version` shows 2.6.x (system Ruby)
```bash
eval "$(rbenv init -)"
rbenv global 3.4.5
rbenv rehash
```

### `pod: command not found`
```bash
# Ensure rbenv Ruby is active first
rbenv shell 3.4.5
gem install cocoapods
rbenv rehash
```

### `node --version` shows wrong version (Homebrew Node)
```bash
brew uninstall node   # remove conflicting Homebrew node
source ~/.zprofile    # reload nvm
nvm use 20
```

### Jenkins agent not connecting after reboot
```bash
# Check if launchd is managing it
ps -ef | grep agent.jar | grep -v grep
# PPID should be 1

# Check log
tail -50 /Users/dk/jenkins-agent/agent.log

# Restart manually
sudo launchctl bootout system /Library/LaunchDaemons/io.jenkins.agent.plist
sudo launchctl bootstrap system /Library/LaunchDaemons/io.jenkins.agent.plist
```

### `Bootstrap failed: 5: Input/output error` on macOS Tahoe
This is a known macOS Tahoe quirk — the service starts despite the error.
Verify with `ps -ef | grep agent.jar`. If PPID=1, launchd owns it and it will
survive reboots.

### CocoaPods `pod install` fails with unicode error
```bash
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
```
Add both lines to `~/.zprofile` permanently.
