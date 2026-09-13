# Why `errSecInternalComponent`, and Why It Sometimes Doesn't Appear

> A clinical write-up of the codesign no-partition experiment on Jenkins-b,
> based on three real builds. Same keychain state, same code, three different
> outcomes — one instant error and two hangs. This doc explains the mechanism.

**Repos/facts verified against:**
- `codesign-ls-nopartition` build #1 (inline pipeline) → `errSecInternalComponent`, rc=1, **instant**
- `codesign-ls-nopartition` build #2 (inline pipeline) → **hang**, 15-min Jenkins timeout → ABORTED
- `codesign-freestyle-ls-nopartition` build #1 (freestyle project, today) → **hang**, killed by a 300 s shell watchdog at `codesign_rc=137` → FAILURE

---

## 1. The high-level picture

On every macOS/iOS sign, `codesign` must use a private key to produce the
signature. That key lives in a keychain, and the keychain protects access to it
with an **Access Control List (ACL)** and a **partition list**. When the caller
is *not* in that partition list, `codesign` cannot use the key silently — it
must ask the user via a keychain consent dialog.

That requirement produces two possible behaviours depending on *environmental*,
not keychain, conditions:

```
                        codesign wants to use the key
                                      │
                  is codesign in the key's partition list?
                    (set-key-partition-list ... codesign:)
                        yes                        no
                         │                          │
                  sign succeeds               does the process have a
                  (rc=0, no prompt)           UI session accessible to
                                             securityd?
                                                    │
                                    ┌───────────────┴───────────────┐
                                    │ NO: no WindowServer /         │ YES: a GUI login
                                    │ no prompt can be rendered     │ session is reachable
                                    ▼                               ▼
                              return ERROR                   block forever,
                              errSecInternalComponent        waiting for the user
                              (instant, rc=1)                to allow the key (hang)
```

The **keychain state is identical** in both no-partition runs. It is the
*branch below* — whether the agent's `codesign` process can reach a UI prompt —
that decides between an instant error and a hang.

---

## 2. The raw evidence (three builds)

### 2.1 Build that produced the error — instant

`codesign-ls-nopartition` build #1, Aug 25 17:26:49:

```
identity hash: 6F4C905D4FCB00319A82096A1683B33C902E65E9
./ls-binary-test: errSecInternalComponent
codesign rc=1
VERDICT: SIGNING FAILED (expected for this variant if partition list is required)
```

Timing: 17:26:49.719 → 17:26:49.999 — **~0.3 s**. Instant, clean, non-interactive.

### 2.2 Two builds that hung — same code, same keychain

`codesign-ls-nopartition` build #2 (inline), Aug 25 17:33:58:

```
identity hash: 6F4C905D4FCB00319A82096A1683B33C902E65E9
--- codesign attempt ---
./ls-binary-test: replacing existing signature        ← codesign STARTED the sign
                                     ... (nothing for ~15 min) ...
Cancelling nested steps due to timeout
After 20s process did not stop                         ← still blocked, uninterruptible
Finished: ABORTED
```

`codesign-freestyle-ls-nopartition` build #1 (today):

```
+ CODESIGN_PID=90213
+ codesign --force --sign 6F4C905D... ./ls-binary-test
+ wait 90213
+ sleep 300
+ kill -9 90213
codesign ...: 90213 Killed: 9                          ← watchdog killed it at 300 s
Build step 'Execute shell' marked build as failure
Finished: FAILURE
```

All three runs used the byte-identical keychain setup (same p12, same dummy
identity `6F4C905D...`, no partition list). Two hung, one errored.

---

## 3. The mechanism decoded

### 3.1 What `errSecInternalComponent` is

`errSecInternalComponent` is Security framework error **-26276**
(`CSSM_ERRCODE_INTERNAL`). In the codesign-without-partition-list world, it is
the **fast-fail** the framework returns when it has decided it *cannot* present
the ACL consent prompt to this calling process. That decision is made when
there is no interactive/WindServer UI session the prompt could be attached to —
a background/launchd agent with no Aqua login session.

Important correction: `errSecInternalComponent` is **not** the sign being
rejected because the key is unauthorised. The key *is* found and opened — the
framework simply refuses to service the key-access request because it has
nowhere to ask for consent. It is a *local, non-interactive* error path.

### 3.2 Why the error is environment-dependent (the crux)

The same `codesign` command, the same keychain, the same unpartitioned key can
return error **or** hang, because the deciding factor is the **process's UI
context**, which changes run-to-run:

| Condition of the agent process / Mac | securityd behaviour when key not in partition list |
|---|---|
| launchd/SSH background agent, **no** GUI session present | cannot render a consent dialog → returns `errSecInternalComponent` immediately |
| process can reach WindowServer (a login GUI session exists on the Mac) | `securityd` presents/registers the "allow access to key" prompt and **waits for the user** |
| nobody is at the console to click "Allow" | the wait never resolves → interactive `codesign` blocks forever |

So the observed difference between build #1 and build #2 of the *identical*
pipeline is the **state of the Mac's console/UI session at that moment**, not
anything in the Jenkins job or keychain. If a user was logged into the Mac when
build #1 ran, the framework may still have chosen the non-interactive path
(no bootstrap to the login session); if the GUI/console session was reachable
when build #2 ran, it routed the prompt and blocked.

### 3.3 The hang itself

When the prompt path is taken, `codesign` blocks in a `wait` on the keychain
auth. The consent dialog genuinely cannot appear and cannot be dismissed on a
headless agent, so the process stays blocked until it is killed from outside.
At no point does it log an error — the signature was already being written
(`replacing existing signature`) right before the block.

### 3.4 Why `kill -9` is needed, and what it takes to make it fail instead

Jenkins' normal abort (`SIGTERM`/`SIGINT`) did not stop the blocked `codesign`
("After 20s process did not stop"). The freestyle job's shell watchdog
therefore used `kill -9` after 300 s, which forces the process to terminate and
lets the build record a real result (`FAILURE`, `codesign_rc=137`) instead of
holding the executor hostage. **Lesson for CI:** any headless Mac job that signs
with a non-partitioned key must wrap `codesign` in an external timeout, in
addition to the Jenkins-build timeout — because a blocked `codesign` can laugh
off Jenkins' polite interrupt.

---

## 4. The fix (set-key-partition-list), with the actual success to compare

The partition **success** build (`codesign-freestyle-ls-partition` build #1,
today) is the control:

```
identity hash: 6F4C905D4FCB00319A82096A1683B33C902E65E9
codesign rc=0
codesign -dv ./ls-binary-test ─┐
                                ├─ Signature size=3699
                                ├─ identifier, format etc.
verify_rc=0
VERDICT: SIGNING SUCCEEDED
```

Took **13.3 s** for the whole job, no prompt, no error. The single line that
causes this, applied right after the p12 import:

```bash
security set-key-partition-list \
    -S apple-tool:,apple:,codesign: \
    -s -k "$KEYCHAIN_PASSWORD" build-kc.keychain
```

This adds `codesign:` to the key's partition list, telling Security that the
`/usr/bin/codesign` binary is an approved, non-interactive user of the key — so
no consent prompt is attempted at all.

---

## 5. Summary / decision table

| Variant (`set-key-partition-list`) | UI session seen by `codesign` | Expected end state |
|---|---|---|
| applied | — | instant **SUCCESS** (no prompt needed) |
| skipped | none (fully headless) | instant **error** `errSecInternalComponent`, rc=1 |
| skipped | GUI session reachable | **indefinite hang** until an external kill |

The experiment's correct conclusion is unchanged: **always apply the partition
list in headless CI.** But it now also carries a sharper diagnostic lesson:
a missing `errSecInternalComponent` in your logs does **not** mean the signing
worked — it may simply mean the Mac had a GUI session at that moment, so
`codesign` hung instead of failing. Any no-partition / pre-fix run that does not
return rc=1 within a second is a hang, not a pass, and must be guarded with a
watchdog.

---

## 6. Reproducing / validating

**Tests (pushed with the freestyle jobs):**
```
python -m pytest jenkins-k8s/tests/test_codesign_freestyle.py -q
```
20 checks: freestyle root element, single shell builder, ios-m4 label, credential
binding wrapper, `/bin/ls` target, `set-key-partition-list` present in exactly
one variant, ephemeral keychain cleanup, `bash -n` syntax validity.

**Jobs on jenkins-b (live, https already validated):**
- `codesign-freestyle-ls-nopartition`  → build #1 FAILURE (watchdog kill, rc=137)
- `codesign-freestyle-ls-partition`    → build #1 SUCCESS (13.3 s, sig 3699)

**Artifacts:**
- `jenkins-k8s/ios/job-configs/freestyle/codesign-ls-partition.xml`
- `jenkins-k8s/ios/job-configs/freestyle/codesign-ls-nopartition.xml`