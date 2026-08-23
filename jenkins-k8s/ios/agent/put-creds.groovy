// Creates ios-dummy-p12 / ios-dummy-p12-pass on jenkins-b via /scriptText.
// Placeholders __PASS__ and __B64__ are substituted by scripts/pulse creds put-p12.
//
// Reflective Class.forName is deliberate:
//  * credentials-plugin 1502.v… dropped com.cloudbees…Secret/StringCredentialsImpl;
//    the live impls are org.jenkinsci.plugins.plaincredentials.impl.*
//  * plugin classes are invisible to the script console compiler, so even
//    plain imports fail — runtime lookup through the uberClassLoader works.

def cl(n) { return Class.forName(n, true, jenkins.model.Jenkins.get().pluginManager.uberClassLoader) }

def ScopeCls = cl("com.cloudbees.plugins.credentials.CredentialsScope")
def HSecret  = cl("hudson.util.Secret")
def SB       = cl("com.cloudbees.plugins.credentials.SecretBytes")
def SCP      = cl("com.cloudbees.plugins.credentials.SystemCredentialsProvider")
def SCI      = cl("org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl")
def FCI      = cl("org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl")
def g        = cl("com.cloudbees.plugins.credentials.domains.Domain").getMethod("global").invoke(null)
def GLOBAL   = ScopeCls.getField("GLOBAL").get(null)

def secret   = HSecret.getMethod("fromString", String).invoke(null, "__PASS__")
def raw      = java.util.Base64.decoder.decode("__B64__")
// wrap explicitly or Groovy spreads the byte[] into one arg-per-byte
def sbargs   = [raw] as Object[]
def sbytes   = SB.getMethod("fromRawBytes", byte[].class).invoke(null, sbargs)

def prov = SCP.getMethod("getInstance").invoke(null)
def store = prov.store   // StoreImpl — resolve dynamically; getStore() is not on the provider

def passCred = SCI.getConstructor(ScopeCls, String, String, HSecret)
        .newInstance(GLOBAL, "ios-dummy-p12-pass", "Dummy signing cert password (direct)", secret)
store.addCredentials(g, passCred)

def fileCred = FCI.getConstructor(ScopeCls, String, String, String, SB)
        .newInstance(GLOBAL, "ios-dummy-p12", "Dummy signing identity p12 (direct)", "dist.p12", sbytes)
store.addCredentials(g, fileCred)

println("CREATED")
