# iOS CI/CD Pipeline Design — Jenkins + Mac Mini Agent

## Overview

Extend the existing Jenkins CI/CD system (running on Kubernetes) with a macOS
JNLP agent on the Mac Mini (`192.168.1.64`) to build, test, scan, and distribute
iOS apps using the same Jenkins C controller already deployed at
`http://192.168.1.10:30881`.

**Sample app:** Apple's [Fruta](https://developer.apple.com/documentation/swiftui/fruta_building_a_feature-rich_app_with_swiftui)
(SwiftUI, Swift Package Manager dependencies) — the iOS equivalent of sunflower.

---

## Mac Mini Agent Setup

### Hardware
- Apple M2, 8 GB RAM, macOS 15.7.3 Sequoia
- Host: `192.168.1.64`, user: `dk`

### Prerequisites to Install

```bash
# 1. Xcode — install from App Store, then accept license
sudo xcodebuild -license accept

# 2. Homebrew
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile

# 3. rbenv + Ruby 3.x (system Ruby 2.6 is too old for Fastlane)
brew install rbenv ruby-build
rbenv install 3.2.2 && rbenv global 3.2.2
echo 'eval "$(rbenv init -)"' >> ~/.zprofile

# 4. Fastlane
gem install fastlane --no-document

# 5. SwiftLint (SAST)
brew install swiftlint

# 6. Semgrep (SAST)
pip3 install semgrep

# 7. LicensePlist (license compliance)
brew install licenseplist

# 8. Java 21 is already present — Jenkins agent jar uses it
java -jar ~/jenkins-agent.jar \
  -url http://192.168.1.10:30881 \
  -secret <JNLP_SECRET> \
  -name ios-agent \
  -workDir /Users/dk/jenkins-agent
```

### Jenkins C Node Configuration

Add a new Permanent Agent in Jenkins C:
- Name: `ios-agent`
- Remote root: `/Users/dk/jenkins-agent`
- Label: `ios-agent`
- Launch method: Launch agent by connecting it to the controller

---

## Why Fastlane over Pure Jenkinsfile

| Concern | Jenkinsfile (xcodebuild) | Fastlane |
|---|---|---|
| Code signing | Manual cert/profile shell commands | `match` — git-backed cert store, one line |
| Build number | `agvtool new-version -all` | `increment_build_number` action |
| TestFlight upload | Multi-step `altool`/`notarytool` | `pilot` — single action with retry |
| Developer reuse | Not portable (needs Jenkins) | `bundle exec fastlane beta` from any Mac |
| App Store metadata | Not supported | `deliver` action |
| Parallel lanes | Manual | Native `lane` composition |

**Rule of thumb:** Jenkins owns the schedule, triggers, reporting, and notifications.
Fastlane owns everything iOS-specific. Jenkins calls `bundle exec fastlane <lane>`.

---

## Architecture

```
Jenkins C Controller (k8s, port 30881)
        │
        │  JNLP TCP (port 30500)
        ▼
Mac Mini (192.168.1.64) — ios-agent label
  ├── Xcode + xcodebuild
  ├── Fastlane (match, gym, pilot, scan, slather)
  ├── SwiftLint
  ├── Semgrep
  └── LicensePlist
```

---

## Pipeline 1 — Jenkinsfile-First (xcodebuild)

Uses `xcodebuild` directly for full transparency. Fastlane is NOT required.
Best for teams who want every step visible in the Jenkinsfile with no magic.

### Stages

```
Checkout → Validate → Build IPA → Unit Tests → SAST → License →
Archive + Nexus Upload → Notify
```

### `jenkins-k8s/ios/option-1-jenkinsfile/Jenkinsfile`

```groovy
pipeline {
    agent { label 'ios-agent' }

    environment {
        APP_NAME        = 'Fruta'
        SCHEME          = 'Fruta'
        WORKSPACE       = 'Fruta.xcodeproj'
        NEXUS_CREDS     = credentials('nexus-creds')
        JFROG_CREDS     = credentials('jfrog-creds')
        BUILD_VERSION   = "${env.BUILD_NUMBER}"
    }

    stages {
        stage('Checkout') {
            steps {
                git url: 'file:///Users/dk/Documents/git/Fruta', branch: 'main'
                script {
                    env.GIT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.IPA_NAME = "${env.APP_NAME}-${env.GIT_SHA}-${env.BUILD_NUMBER}.ipa"
                }
            }
        }

        stage('Validate Environment') {
            steps {
                sh '''
                    set -e
                    xcodebuild -version
                    xcrun simctl list devices available | head -5
                    swiftlint version
                    semgrep --version
                '''
            }
        }

        stage('Build IPA') {
            steps {
                sh """
                    # Inject build number
                    agvtool new-version -all ${env.BUILD_NUMBER} || true

                    # Build archive
                    xcodebuild archive \
                        -project ${env.WORKSPACE} \
                        -scheme ${env.SCHEME} \
                        -configuration Release \
                        -archivePath build/${env.APP_NAME}.xcarchive \
                        -allowProvisioningUpdates \
                        CODE_SIGN_IDENTITY="Apple Development" \
                        | xcpretty || true

                    # Export IPA
                    xcodebuild -exportArchive \
                        -archivePath build/${env.APP_NAME}.xcarchive \
                        -exportPath build/ \
                        -exportOptionsPlist ios/ExportOptions.plist \
                        | xcpretty || true
                """
                archiveArtifacts artifacts: "build/*.ipa", allowEmptyArchive: true
            }
        }

        stage('Unit Tests') {
            steps {
                sh """
                    xcodebuild test \
                        -project ${env.WORKSPACE} \
                        -scheme ${env.SCHEME} \
                        -destination 'platform=iOS Simulator,name=iPhone 15,OS=latest' \
                        -resultBundlePath build/TestResults.xcresult \
                        | xcpretty --report junit --output reports/test-results.xml || true
                """
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'reports/test-results.xml'
                }
            }
        }

        stage('SAST') {
            parallel {
                stage('SwiftLint') {
                    steps {
                        sh 'swiftlint lint --reporter json > reports/swiftlint.json || true'
                        sh 'swiftlint lint --reporter html > reports/swiftlint.html || true'
                    }
                }
                stage('Semgrep') {
                    steps {
                        sh '''
                            mkdir -p reports
                            semgrep --config=auto \
                                --json \
                                --output=reports/semgrep-ios.json \
                                --include="*.swift" \
                                --exclude="build/" . || true
                            python3 /opt/semgrep-to-html.py \
                                reports/semgrep-ios.json \
                                reports/semgrep-ios.html || true
                        '''
                    }
                }
            }
            post {
                always {
                    publishHTML([
                        allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
                        reportDir: 'reports', reportFiles: 'swiftlint.html',
                        reportName: 'SwiftLint Report'
                    ])
                    publishHTML([
                        allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
                        reportDir: 'reports', reportFiles: 'semgrep-ios.html',
                        reportName: 'Semgrep iOS SAST'
                    ])
                }
            }
        }

        stage('License Compliance') {
            steps {
                sh '''
                    licenseplist --output-path reports/licenses || true
                    # Block AGPL/GPL-only packages
                    grep -ri "GPL" reports/licenses/ && echo "WARNING: GPL license detected" || true
                '''
            }
        }

        stage('Archive + Nexus Upload') {
            steps {
                script {
                    def ipaPath = sh(
                        script: "find build -name '*.ipa' | head -1",
                        returnStdout: true
                    ).trim()
                    if (ipaPath) {
                        sh """
                            cp "${ipaPath}" "${env.IPA_NAME}"
                            curl -sf \
                                -u "${env.NEXUS_CREDS_USR}:${env.NEXUS_CREDS_PSW}" \
                                --upload-file "${env.IPA_NAME}" \
                                "http://nexus.utilities.svc.cluster.local:8081/repository/ios-releases/${env.IPA_NAME}"
                            echo "Uploaded: ${env.IPA_NAME}"
                        """
                    } else {
                        echo "No IPA found — skipping Nexus upload"
                    }
                }
            }
        }

        stage('JFrog Upload') {
            steps {
                script {
                    def ipaPath = sh(
                        script: "find build -name '*.ipa' | head -1",
                        returnStdout: true
                    ).trim()
                    if (ipaPath) {
                        sh """
                            curl -sf \
                                -u "${env.JFROG_CREDS_USR}:${env.JFROG_CREDS_PSW}" \
                                -X PUT \
                                --upload-file "${ipaPath}" \
                                "http://192.168.1.10:30082/artifactory/example-repo-local/ios/${env.IPA_NAME}" \
                                && echo "Uploaded to JFrog: ${env.IPA_NAME}"
                        """
                    } else {
                        echo "No IPA found — skipping JFrog upload"
                    }
                }
            }
        }
    }

    post {
        failure {
            script {
                def failedAt = env.FAILED_STAGE ?: 'Unknown'
                sh """
                    FAILED_STAGE='${failedAt}' \
                    ERROR_SNIPPET='' \
                    LOG_TAIL='' \
                    BUILD_NUMBER='${env.BUILD_NUMBER}' \
                    JOB_NAME='${env.JOB_NAME}' \
                    /opt/llm-analysis.sh
                """
            }
            emailext(
                subject: "[iOS FAILURE] \${env.JOB_NAME} #\${env.BUILD_NUMBER}",
                to: 'hello.dk@outlook.com',
                mimeType: 'text/html',
                body: '${SCRIPT, template="failure-email.groovy"}'
            )
        }
        success {
            emailext(
                subject: "[iOS SUCCESS] \${env.JOB_NAME} #\${env.BUILD_NUMBER} — IPA ready",
                to: 'hello.dk@outlook.com',
                mimeType: 'text/html',
                body: '${SCRIPT, template="success-email.groovy"}'
            )
        }
        cleanup {
            deleteDir()
        }
    }
}
```

---

## Pipeline 2 — Fastlane-First (Recommended)

Jenkins calls `fastlane` lanes. All iOS-specific logic lives in `fastlane/Fastfile`.
Code signing via `fastlane match` (certs stored in a private git repo).

### Directory structure

```
ios-app/
├── Fruta.xcodeproj
├── fastlane/
│   ├── Fastfile          # Lane definitions
│   ├── Appfile           # App ID, team ID
│   └── Matchfile         # Code signing config
└── jenkins-k8s/ios/option-2-fastlane/Jenkinsfile
```

### `fastlane/Fastfile`

```ruby
default_platform(:ios)

platform :ios do

  before_all do
    setup_ci if ENV['CI']   # configures keychain for CI
  end

  # ── Code signing (match reads certs from private git repo) ──────────────
  lane :setup_signing do
    match(
      type: "development",
      readonly: is_ci,
      git_url: "git@github.com:hellodk/ios-certs.git"
    )
  end

  # ── Build IPA ────────────────────────────────────────────────────────────
  lane :build do
    increment_build_number(build_number: ENV['BUILD_NUMBER'] || "1")
    setup_signing
    gym(
      scheme: "Fruta",
      configuration: "Release",
      export_method: "development",
      output_directory: "build",
      output_name: "Fruta-#{ENV['BUILD_NUMBER']}.ipa",
      xcpretty_args: "--color"
    )
  end

  # ── Unit Tests ───────────────────────────────────────────────────────────
  lane :test do
    scan(
      scheme: "Fruta",
      device: "iPhone 15",
      output_directory: "reports",
      output_types: "junit,html",
      output_files: "test-results.xml,test-results.html",
      fail_build: false
    )
  end

  # ── SAST (SwiftLint + Semgrep) ───────────────────────────────────────────
  lane :sast do
    sh("swiftlint lint --reporter json > reports/swiftlint.json || true")
    sh("semgrep --config=auto --json --output=reports/semgrep-ios.json " \
       "--include='*.swift' --exclude='build/' . || true")
  end

  # ── Upload to Nexus ──────────────────────────────────────────────────────
  lane :upload_nexus do |options|
    ipa_path = lane_context[SharedValues::IPA_OUTPUT_PATH]
    sh("curl -sf -u #{ENV['NEXUS_USER']}:#{ENV['NEXUS_PASS']} " \
       "--upload-file '#{ipa_path}' " \
       "'http://192.168.1.10:30081/repository/ios-releases/#{File.basename(ipa_path)}'")
  end

  # ── Upload to JFrog Artifactory ──────────────────────────────────────────
  lane :upload_jfrog do
    ipa_path = lane_context[SharedValues::IPA_OUTPUT_PATH]
    ipa_name = File.basename(ipa_path)
    sh("curl -sf " \
       "-u #{ENV['JFROG_USER']}:#{ENV['JFROG_PASS']} " \
       "-X PUT --upload-file '#{ipa_path}' " \
       "'http://192.168.1.10:30082/artifactory/example-repo-local/ios/#{ipa_name}' " \
       "&& echo 'Uploaded to JFrog: #{ipa_name}'")
  end

  # ── Upload to TestFlight ─────────────────────────────────────────────────
  lane :testflight do
    upload_to_testflight(
      ipa: lane_context[SharedValues::IPA_OUTPUT_PATH],
      skip_waiting_for_build_processing: true
    )
  end

  # ── Full CI lane ─────────────────────────────────────────────────────────
  lane :ci do
    test
    build
    sast
    upload_nexus
    upload_jfrog
  end

  # ── Release to TestFlight ────────────────────────────────────────────────
  lane :release do
    test
    build
    sast
    testflight
  end

end
```

### `jenkins-k8s/ios/option-2-fastlane/Jenkinsfile`

```groovy
pipeline {
    agent { label 'ios-agent' }

    environment {
        NEXUS_CREDS = credentials('nexus-creds')
        MATCH_PASSWORD = credentials('ios-match-password')   // match encryption passphrase
        FASTLANE_DONT_STORE_PASSWORD = '1'
    }

    stages {
        stage('Checkout') {
            steps {
                git url: 'file:///Users/dk/Documents/git/Fruta', branch: 'main'
            }
        }

        stage('Bundle Install') {
            steps {
                sh 'bundle install --path vendor/bundle'
            }
        }

        stage('Test') {
            steps {
                sh 'bundle exec fastlane test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'reports/test-results.xml'
                }
            }
        }

        stage('Build') {
            steps {
                sh 'bundle exec fastlane build'
            }
        }

        stage('SAST') {
            steps {
                sh 'bundle exec fastlane sast'
            }
            post {
                always {
                    publishHTML([
                        allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
                        reportDir: 'reports', reportFiles: 'swiftlint.html',
                        reportName: 'SwiftLint'
                    ])
                }
            }
        }

        stage('Upload') {
            steps {
                withCredentials([
                    usernamePassword(credentialsId: 'nexus-creds',
                        usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS'),
                    usernamePassword(credentialsId: 'jfrog-creds',
                        usernameVariable: 'JFROG_USER', passwordVariable: 'JFROG_PASS')
                ]) {
                    sh 'bundle exec fastlane upload_nexus'
                    sh 'bundle exec fastlane upload_jfrog'
                }
            }
        }
    }

    post {
        failure {
            script {
                sh """
                    FAILED_STAGE='${env.FAILED_STAGE ?: "Unknown"}' \
                    BUILD_NUMBER='${env.BUILD_NUMBER}' \
                    JOB_NAME='${env.JOB_NAME}' \
                    ERROR_SNIPPET='' LOG_TAIL='' \
                    /opt/llm-analysis.sh
                """
            }
            emailext(
                subject: "[iOS FAILURE] \${env.JOB_NAME} #\${env.BUILD_NUMBER}",
                to: 'hello.dk@outlook.com',
                mimeType: 'text/html',
                body: '${SCRIPT, template="failure-email.groovy"}'
            )
        }
        success {
            emailext(
                subject: "[iOS SUCCESS] \${env.JOB_NAME} #\${env.BUILD_NUMBER} — IPA ready",
                to: 'hello.dk@outlook.com',
                mimeType: 'text/html',
                body: '${SCRIPT, template="success-email.groovy"}'
            )
        }
        cleanup { deleteDir() }
    }
}
```

---

## Security Equivalence Table

| Android (Jenkins/K8s) | iOS (Mac Mini JNLP) |
|---|---|
| Semgrep | Semgrep (Swift rules) |
| Android Lint | SwiftLint |
| SpotBugs | No equivalent (Swift memory-safe) |
| OWASP Dependency-Check | `swift package audit` (SPM) |
| License checker script | LicensePlist |
| SonarQube | SonarCloud (cloud) or SonarQube with Swift plugin |
| Nexus upload | Nexus upload (APK/IPA) |
| JFrog Artifactory | JFrog upload (example-repo-local/ios/) |

---

## Mac Mini Agent Setup Steps (Full)

```bash
# --- Run on Mac Mini (ssh dk@192.168.1.64) ---

# 1. Install Xcode from App Store, then:
sudo xcodebuild -license accept

# 2. Homebrew
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
source ~/.zprofile

# 3. rbenv (newer Ruby for Fastlane)
brew install rbenv ruby-build
rbenv install 3.2.2 && rbenv global 3.2.2
echo 'eval "$(rbenv init - zsh)"' >> ~/.zprofile
source ~/.zprofile

# 4. Fastlane
gem install fastlane bundler --no-document

# 5. SwiftLint
brew install swiftlint

# 6. xcpretty (nicer xcodebuild output)
gem install xcpretty --no-document

# 7. Download Jenkins agent jar
curl -sO http://192.168.1.10:30881/jnlpJars/agent.jar

# 8. Connect to Jenkins C (get secret from Jenkins UI → Nodes → ios-agent)
java -jar agent.jar \
  -url http://192.168.1.10:30881 \
  -secret <JNLP_SECRET_FROM_UI> \
  -name ios-agent \
  -workDir /Users/dk/jenkins-agent

# 9. (Optional) Run as launchd service for auto-start on reboot
# Create ~/Library/LaunchAgents/com.jenkins.agent.plist
```

---

## Pipeline 3 — React Native (Node.js + CocoaPods)

Targets apps built with React Native (JavaScript + native iOS modules). The defining characteristic is a **two-phase dependency install**: JavaScript packages first (`npm ci`), then iOS native dependencies (`pod install`). CocoaPods reads podspecs generated by the JS packages, so the order is mandatory.

**Reference app:** [Mattermost Mobile](https://github.com/mattermost/mattermost-mobile) — open-source, React Native, `package.json` + `ios/Podfile`.

### Additional Mac Mini prerequisites

| Tool | Install | Purpose |
|---|---|---|
| nvm | `curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh \| bash` | Node version manager |
| Node.js 20 LTS | `nvm install --lts` | JS runtime + npm |
| CocoaPods | `gem install cocoapods` | iOS native deps |
| jest-junit | `npm install -g jest-junit` | JUnit XML output from Jest |
| license-checker | `npm install -g license-checker` | JS license audit |

All provisioned via `ansible/setup-ios-agent.yml`.

### Key pipeline differences vs pure Swift

| Stage | Swift (Options 1/2) | React Native (Option 3) |
|---|---|---|
| Dependencies | `xcodebuild -resolvePackageDependencies` (SPM) | `npm ci` → `pod install` (mandatory order) |
| Build target | `.xcodeproj` | `.xcworkspace` (generated by CocoaPods) |
| Unit tests | xcodebuild test | Jest (JS) + xcodebuild test (native) |
| SAST | SwiftLint + Semgrep | ESLint + SwiftLint + Semgrep |
| License check | LicensePlist (Swift deps) | license-checker (JS) + LicensePlist (native) |
| Cleanup | `deleteDir()` | `rm -rf node_modules ios/Pods` before `deleteDir()` |

### Stage flow

```
Checkout
  → Validate Environment (node, npm, pod, xcodebuild)
  → JS Dependencies        (npm ci)
  → iOS Native Deps        (pod install — must follow npm ci)
  → Build IPA              (xcodebuild -workspace .xcworkspace)
  → Unit Tests             (Jest parallel with xcodebuild test)
  → SAST                   (ESLint parallel with SwiftLint + Semgrep)
  → License Compliance     (license-checker parallel with LicensePlist)
  → Upload Artifacts       (Nexus + JFrog)
```

### `jenkins-k8s/ios/option-3-react-native/Jenkinsfile`

See `jenkins-k8s/ios/option-3-react-native/Jenkinsfile` in the repo.

Key shell pattern for nvm inside Jenkins sh steps (nvm is a shell function, not a binary):

```sh
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
npm ci
```

### Why `pod install` must run after `npm ci`

React Native packages that include native iOS code ship a `.podspec` file. When you run `npm ci`, these podspecs land in `node_modules/`. CocoaPods reads them during `pod install` — if you run `pod install` first, it finds no podspecs and your native modules are silently missing.

---

## Why Fastlane is Recommended (Summary)

1. **`match`** — Solves the hardest iOS CI problem: code signing. Stores certificates
   and provisioning profiles encrypted in a git repo. Any Mac agent pulls them
   automatically. No more "certificate expired" surprises.

2. **Portable** — A developer can run `fastlane ci` locally and get the exact same
   result as Jenkins. Pure Jenkinsfile builds only work in Jenkins.

3. **`gym` abstracts xcodebuild complexity** — Archive, export, re-sign in one action
   vs 40+ lines of shell flags.

4. **`pilot` handles TestFlight edge cases** — Notarization wait, processing delay,
   retry logic — all handled. With curl/altool you write that yourself.

5. **Lane reuse** — `release` lane calls `build` + `testflight`. `ci` lane calls
   `build` + `nexus`. No Groovy duplication.
