# Mobile Code Signing — iOS & Android Field Guide

> Written from a live CI bring-up: every command and every error in this document
> was produced by real pipelines on an M4 Mac mini (Xcode 26 / macOS Tahoe) driving
> Jenkins, plus the matching Android equivalents. Companion deep-dive blog:
> `~/Documents/blogs/2026-08-25-mobile-code-signing/`.

---

## Table of contents

1. [iOS: what code signing actually does](#ios-what-code-signing-actually-does)
2. [Trust chain & the WWDR certificate](#trust-chain--the-wwdr-certificate)
3. [Identities: CSR, certificates, .p12 files](#identities-csr-certificates-p12-files)
4. [The keychain: ACLs, search list, default keychain](#the-keychain-acls-search-list-default-keychain)
5. [Partition lists — the headless-CI unlock](#partition-lists--the-headless-ci-unlock)
6. [App IDs & provisioning profiles](#app-ids--provisioning-profiles)
7. [Archives, entitlements & verification](#archives-entitlements--verification)
8. [Experiment: partition vs no-partition (real results)](#experiment-partition-vs-no-partition-real-results)
9. [Android: APK/AAB anatomy and the build pipeline](#android-apkaab-anatomy-and-the-build-pipeline)
10. [Keystores, keytool & Gradle signing configs](#keystores-keytool--gradle-signing-configs)
11. [Signature schemes v1–v4](#signature-schemes-v1v4)
12. [Play App Signing: upload key vs app signing key](#play-app-signing-upload-key-vs-app-signing-key)
13. [iOS ↔ Android translation table](#ios--android-translation-table)
14. [CI bootstrap cheat sheet](#ci-bootstrap-cheat-sheet)
15. [Common errors, decoded](#common-errors-decoded)

---

## iOS: what code signing actually does

Every iOS executable must carry a cryptographic signature before the kernel runs it.
On every launch, two questions get answered:

| Question | Mechanism |
|---|---|
| Was the binary modified since signing? (**integrity**) | Every bundle file is hashed into `_CodeSignature/CodeResources`; the Mach-O itself is hashed page-by-page into a **CodeDirectory** embedded in the executable |
| Who signed it? (**authenticity**) | The signature is produced by a private key whose certificate chains back to Apple |
| What is it allowed to do? (**policy**) | **Entitlements** are embedded inside the signature; the kernel module **AMFI** checks them against the provisioning profile at install time *and* runtime |

One flipped byte anywhere → invalid signature → the process is killed before
`main()` runs. There is no opt-out on device builds (simulator builds are lax).

## Trust chain & the WWDR certificate

```
Apple Root CA                      ← burned into every Apple device
   └── Apple Worldwide Developer Relations (WWDR) Intermediate CA
          ├── iPhone Developer: You (TEAMID)        ← 1 year, devices only
          └── iPhone Distribution: You (TEAMID)     ← 1 year, per profile type
                 └── your signed .app / .xcarchive
```

- Certificates are issued by Apple through the developer portal; you never send
  Apple your private key.
- Older Xcode setups required manually installing the **WWDR intermediate** into
  the keychain. Without it, verification fails with errors like
  `unable to build chain to self-signed root` or `CSSMERR_TP_CERT_REVOKED`.
  Modern Xcode bundles it — hand-built CI images often don't.

## Identities: CSR, certificates, .p12 files

A **signing identity** = private key (in your keychain) + certificate (issued by
Apple) bound together. The lifecycle:

1. **Generate a CSR locally** — Keychain Access or:
   ```bash
   openssl req -new -key dist.key -out CertificateSigningRequest -subj "/CN=YourBank Dist/"
   ```
2. **Upload the CSR to developer.apple.com** → Apple signs your public key with
   the WWDR CA → download the `.cer`.
3. **Import the .cer back into the same keychain** so it pairs with the private key.
4. **Export a `.p12`** (PKCS#12) for CI — one portable file containing the cert
   plus a password-encrypted private key:

   ```bash
   openssl pkcs12 -export -out dist.p12 \
       -inkey dist.key -in dist.crt -certfile ca.crt \
       -passout pass:'DummyCertPass123!'
   ```

Certificate types:

| Type | Runs where | Typical use |
|---|---|---|
| iPhone Developer | registered devices only | daily development, debugger attach |
| iPhone Distribution | per profile type ↓ | ad-hoc OTA, App Store, Enterprise |
| Enterprise (In-House) | any device ($299/yr program) | internal corporate fleets |
| Self-signed dummy | nobody — archive proof only | CI pipelines without an Apple account |

## The keychain: ACLs, search list, default keychain

The keychain is an encrypted store managed by `securityd`. Three facts cause most
CI failures:

1. **Locking** — keychains lock after inactivity/sleep. Locked ⇒ every key access
   fails until `security unlock-keychain -p …`.
2. **Search list vs default** — `codesign` resolves identities by walking the
   *user search list*. A keychain missing from it yields `no identity found`
   even when `--keychain` points at it directly. A broken **default** keychain
   can poison lookups globally:

   ```bash
   security default-keychain -s ~/Library/Keychains/login.keychain-db   # reset
   security list-keychains -d user                                      # inspect
   security list-keychains -d user -s ~/Library/Keychains/login.keychain-db
   ```
3. **ACLs** — importing with `-T /usr/bin/codesign` whitelists codesign in the
   key's access control list so it may use the key without prompting.

Field scars from this repo's bring-up:

- The dummy-signing generator creates its keychain with a **random password**
  (`dummy-kc-$(date +%s | tail -c 8)`) saved to `.keychain_pass`. If that file is
  wiped, the keychain is locked forever — and if it was left as the *default*
  keychain, everything else degrades with it.

## Partition lists — the headless-CI unlock

macOS partitions keys so only approved tooling can use them **without user
interaction**: `apple:` (Apple apps), `apple-tool:` (security tools),
`codesign:`. A daemon (Jenkins agent under launchd) has no GUI session, so a key
whose partition list doesn't include `codesign:` cannot be used silently.

```bash
security set-key-partition-list \
    -S apple-tool:,apple:,codesign: \
    -s -k "$KEYCHAIN_PASSWORD" build-kc.keychain
```

Apply it after import, after unlocking. See the experiment below for what
happens when you skip it — both failure modes are worse than they sound.

## App IDs & provisioning profiles

An **App ID** is `team-id.bundle.pattern`:

- **Explicit** (`ABCDE12345.com.bank.superapp`) — enables capability switches
  (push, Sign in with Apple, associated domains…).
- **Wildcard** (`ABCDE12345.com.bank.*`) — many apps, almost no capabilities.

A **provisioning profile** (`embedded.mobileprovision`, CMS-signed plist)
declares who may sign *what* and where it may run:

```
┌─────────────────────┐ ┌──────────────────┐ ┌────────────────────┐
│ Signing certs (SHA-1)│ │ App ID pattern    │ │ Device UDIDs        │
└──────────┬──────────┘ └────────┬─────────┘ └─────────┬──────────┘
           └─────────────┬───────┴─────────────────────┘
                          ▼
            embedded.mobileprovision
        (+ entitlements copy + expiry date)
                          │
                          ▼
      checked by iOS at install time AND every launch
```

| Profile type | Devices needed? | Store submission | Expiry |
|---|---|---|---|
| Development | yes | no | ~7 days (free acct) / 1 yr |
| Ad Hoc | yes (≤100/yr) | no | 1 yr |
| App Store | no | **yes** | 1 yr |
| Enterprise/In-House | no | no | 1 yr |

## Archives, entitlements & verification

```bash
xcodebuild archive \
  -project Rampage.xcodeproj -scheme Rampage \
  -destination 'generic/platform=iOS' \        # Xcode 26 has NO default destination
  -archivePath build/App.xcarchive \
  CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=TEAMID1234 \
  CODE_SIGN_IDENTITY="iPhone Distribution: YourBank Ltd (TEAMID1234)" \
  OTHER_CODE_SIGN_FLAGS="--keychain build-kc --timestamp=none"
```

Gotchas learned the hard way:

- `-destination` is mandatory for archives on modern Xcode; otherwise
  *"Found no destinations"* — which surrounding `|| true` will happily hide.
- `PRODUCT_BUNDLE_IDENTIFIER=""` (empty override) breaks the build with
  *"Bundle identifier is missing"* — never emit empty overrides.
- xcodebuild **refuses self-signed identities outright**
  (*"Signing certificate … is not valid for code signing"*) even though plain
  `codesign` accepts them. Workaround: archive unsigned, then
  `codesign --force --sign <SHA1> --keychain … --timestamp=none <AppInArchive>`.
- CI Macs frequently miss the platform SDK: `xcodebuild -downloadPlatform iOS`.

Inspecting results:

```bash
codesign -dv build/App.xcarchive/Products/Applications/Rampage.app
# Executable=...Rampage.app/Rampage
# Identifier=com.charcoaldesign.Rampage
# Format=app bundle ... CodeDirectory v=20400 flags=0x0(none) location=embedded
# Signature size=3699
# TeamIdentifier=not set        ← expected for self-signed dummies
```

## Experiment: partition vs no-partition (real results)

Two Jenkins pipelines signed a copy of `/bin/ls` with the same dummy identity in
an ephemeral keychain; the only variable was `set-key-partition-list`.
Job definitions: [`codesign-ls-nopartition.xml`](../job-configs/codesign-ls-nopartition.xml),
[`codesign-ls-partition.xml`](../job-configs/codesign-ls-partition.xml).

| Run | Setup | Result |
|---|---|---|
| NOPARTITION · build 1 | ephemeral KC, poisoned default KC present, KC not in search list | `errSecInternalComponent`, rc=1 in ~0.3 s |
| NOPARTITION · build 2 | clean search list, KC registered, default reset to login | **silent hang** — codesign blocks forever waiting for a GUI auth prompt that cannot appear; killed by job timeout |
| PARTITION · build 2 | identical + `set-key-partition-list` | **rc=0**, instant; `Signature size=3699`, verification passes |

Takeaway: skipping the partition list doesn't produce a tidy error — you get
either an instant denial or a deadlock, depending on whether securityd can route
a prompt. Both mean the build is dead on a headless agent.

## Android: APK/AAB anatomy and the build pipeline

An APK is a ZIP: `classes.dex` (bytecode), `resources.arsc`, `lib/<abi>/*.so`,
`AndroidManifest.xml`, assets. An **AAB** is not installable — Play (or
`bundletool build-apks`) derives optimized split APKs per ABI/density/language,
with a universal fallback for sideloading.

```
Kotlin/Java ──► Gradle (+R8 shrink/obfuscate/dex) ──► unsigned APK/AAB
                                                        │ zipalign FIRST
                                                        ▼
                                              apksigner (v2/v3/v4 block)
                                                        ▼
                                             Play / sideloaded device
```

- **R8** shrinks and obfuscates; upload `mapping.txt` per release so crashes
  retrace — the direct analogue of iOS dSYM symbolication.
- `debug.keystore` (`~/.android/debug.keystore`, alias `androiddebugkey`)
  auto-signs debug installs; signature mismatch vs release forces uninstall.

## Keystores, keytool & Gradle signing configs

Everything iOS splits across keychain + .p12 lives in one file:

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.p12 -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10950

keytool -printcert -jarfile app.apk          # what signed this?
apksigner verify --print-certs app-release.apk
```

```groovy
android {
  signingConfigs {
    release {
      storeFile     = file("upload-keystore.p12")
      storePassword = System.getenv("KS_PASS")     // env vars, never git
      keyAlias      = System.getenv("KS_ALIAS")
      keyPassword   = System.getenv("KEY_PASS")
    }
  }
}
```

Play requires the signing certificate valid past **October 2033**. Lose the
keystore without Play App Signing enrolled and the app can never be updated.

## Signature schemes v1–v4

| Scheme | Since | Signs | Why it exists |
|---|---|---|---|
| v1 (JAR) | 1.0 | individual ZIP entries (META-INF) | original model; entry *names* unsigned → Janus CVE-2017-13156 code injection |
| v2 | 7.0 | whole file via APKSig block between entries and central directory | tamper-proof byte range; fast install-time verify |
| v3 | 9 | v2 + rotation lineage | **key rotation** — new cert provably succeeds old (proof-of-rotation) |
| v4 | 11 | merkle-tree sidecar (.idsig) | incremental/streaming installs, fs-verity |

`apksigner` auto-selects based on `minSdkVersion`; be explicit in CI:

```bash
zipalign -f -p 4 in.apk out.apk     # BEFORE v2/v3 signing, never after
apksigner sign \
  --ks upload-keystore.p12 --ks-key-alias upload \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --min-sdk-version 24 out.apk
```

Hardware note: keys generated inside StrongBox/TEE never leave secure hardware —
Android's mirror of Apple's SEP-protected signing keys.

## Play App Signing: upload key vs app signing key

1. You sign the AAB with the **upload key** — proves authorship to Play.
2. Google strips that signature and re-signs split APKs with the **app signing
   key** (HSM-backed).
3. Rotation becomes routine: lost/expired upload key is a console update;
   compromised app-signing key rotates via v3 proof-of-rotation lineage.

Without enrollment, losing the single keystore is unrecoverable.

## iOS ↔ Android translation table

| Concept | iOS | Android |
|---|---|---|
| Private key home | Keychain item (ACL + partition list) | entry inside KeyStore file |
| Portable credential | `.p12` | the keystore itself |
| Issuer | Apple via WWDR CA | self-signed — you are the root |
| Who may install | provisioning profile (+UDIDs) | signature continuity at update |
| Capabilities | entitlements in signature | manifest permissions (some `signature`-level) |
| CLI signer | `codesign` | `apksigner` (ex-`jarsigner`) |
| Inspect | `codesign -dv`, `security find-identity` | `keytool -printcert`, `apksigner verify` |
| Crash symbols | dSYMs | mapping.txt → retrace |
| Beta channel | TestFlight / Ad-Hoc | internal testing track / direct APK |
| Store re-sign | Apple after review | Play App Signing |
| Key-loss severity | painful (revoke+reissue) | catastrophic unless Play App Signing |
| Headless-CI pain | locked KCs, partitions, GUI prompts | basically none — file-based |

## CI bootstrap cheat sheet

iOS (every build, ephemeral keychain):

```bash
security create-keychain -p "$KC_PASS" build-kc.keychain
security unlock-keychain -p "$KC_PASS" build-kc.keychain
security import "$P12_FILE" -k build-kc.keychain -P "$P12_PASS" \
        -T /usr/bin/codesign -f pkcs12
security list-keychains -d user -s build-kc.keychain \    # search list!
         ~/Library/Keychains/login.keychain-db
security set-key-partition-list -S apple-tool:,apple:,codesign: \
        -s -k "$KC_PASS" build-kc.keychain
# …build…
security delete-keychain build-kc.keychain                # cleanup
```

Android:

```bash
echo "$KEYSTORE_B64" | base64 -d > release.keystore
./gradlew bundleRelease -PKS_PASS="$KS_PASS" -PKS_ALIAS=upload
```

## Common errors, decoded

| Message | Platform | Real meaning | Fix direction |
|---|---|---|---|
| `errSecInternalComponent` | iOS | key ACL/partition denies non-interactive use | unlock + partition list |
| codesign hangs forever | iOS | GUI auth prompt on headless agent | same — the prompt *is* the bug |
| `no identity found` | iOS | KC absent from search list / expired cert | `list-keychains -s`; check dates |
| `Unable to find a destination` | iOS | SDK/platform missing | `xcodebuild -downloadPlatform iOS` |
| `Bundle identifier is missing` | iOS | empty `PRODUCT_BUNDLE_IDENTIFIER=` override | drop empty overrides |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | Android | v2 required, only v1 present | apksigner with min-sdk-aware schemes |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Android | different signing key than installed build | restore correct keystore / uninstall |
| `:signReleaseBundle FAILED` | Android | keystore path/password wrong in CI env | check base64 round-trip + env names |
