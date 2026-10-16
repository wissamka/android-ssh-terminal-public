# sshj + BouncyCastle: reflection-heavy crypto registration
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**

# Vendored terminal emulator (accessed from terminal-view)
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

# MINA 2.19.0: retain only entry points loaded by class name / reflection.
# SecurityUtils.DEFAULT_SECURITY_PROVIDER_REGISTRARS -> no-argument constructors.
-keep,allowoptimization class org.apache.sshd.common.util.security.SunJCESecurityProviderRegistrar { public <init>(); }
-keep,allowoptimization class org.apache.sshd.common.util.security.bouncycastle.BouncyCastleSecurityProviderRegistrar { public <init>(); }
-keep,allowoptimization class org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderRegistrar { public <init>(); }
# BuiltinIoServiceFactoryFactories; production explicitly selects NIO2.
-keep,allowoptimization class org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory { public <init>(); }
# LoggingUtils enumerates fields and matches their original SSH_* names.
-keepclassmembers class org.apache.sshd.common.SshConstants { public static final *** SSH*; }
-keepclassmembers class org.apache.sshd.agent.SshAgentConstants { public static final *** SSH*; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault

# ExceptionUtils.peelException guards these with !OsUtils.isAndroid().
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
# Unused desktop convenience ClientChannel.validateCommandExitStatusCode; the
# backend reads shell exitStatus directly and never calls executeRemoteCommand.
-dontwarn java.rmi.RemoteException
-dontwarn java.rmi.ServerException
# MINA encrypted-file loaders are unused: identities come from the app's SSHJ
# KeyProvider/DeferredSigningKey, with MINA disk identity discovery disabled.
-dontwarn javax.security.auth.login.CredentialException
-dontwarn javax.security.auth.login.FailedLoginException
# Server-only JAAS / GSS authentication. Neither is installed in the client or
# localhost fixture (which supplies explicit password/public-key/interactive auth).
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.auth.login.AppConfigurationEntry$LoginModuleControlFlag
-dontwarn javax.security.auth.login.AppConfigurationEntry
-dontwarn javax.security.auth.login.Configuration
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid
# Optional native Unix agent transport; production sets agentFactory = null.
-dontwarn org.apache.tomcat.jni.Error
-dontwarn org.apache.tomcat.jni.File
-dontwarn org.apache.tomcat.jni.Library
-dontwarn org.apache.tomcat.jni.Local
-dontwarn org.apache.tomcat.jni.Pool
-dontwarn org.apache.tomcat.jni.Socket
# Optional net.i2p provider is detected before use; the app bundles BC Ed25519.
-dontwarn net.i2p.crypto.eddsa.EdDSAEngine
-dontwarn net.i2p.crypto.eddsa.EdDSAKey
-dontwarn net.i2p.crypto.eddsa.EdDSAPrivateKey
-dontwarn net.i2p.crypto.eddsa.EdDSAPublicKey
-dontwarn net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
-dontwarn net.i2p.crypto.eddsa.spec.EdDSAParameterSpec
-dontwarn net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
-dontwarn net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
-dontwarn net.i2p.crypto.eddsa.math.Curve
-dontwarn net.i2p.crypto.eddsa.math.GroupElement
-dontwarn net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec
