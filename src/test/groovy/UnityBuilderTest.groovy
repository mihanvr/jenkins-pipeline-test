import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class UnityBuilderTest extends BasePipelineTest {

    private static final String UNITY_HUB_PATH = 'C:/Program Files/Unity Hub/Unity Hub.exe'

    private static final String PROJECT_VERSION_FILE =
            "m_EditorVersion: 6000.5.8f1\nm_EditorVersionWithRevision: 6000.5.8f1 (7f4a1c2b3d4e)\n"

    private Object unityBuilder
    private Map writtenJson
    private Map writtenFiles
    private List echoed
    private Map unityExecuteArgs

    private Map pipelineEnv
    private List credentialBindings
    private List deletedFiles
    private boolean insideCredentials
    private boolean unityRanInsideCredentials

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots = ['vars'] as String[]
        scriptExtension = 'groovy'
        super.setUp()

        writtenJson = [:]
        writtenFiles = [:]
        echoed = []
        unityExecuteArgs = [:]

        pipelineEnv = [:]
        credentialBindings = []
        deletedFiles = []
        insideCredentials = false
        unityRanInsideCredentials = false

        helper.registerAllowedMethod('error', [String], { String message -> throw new RuntimeException(message) })
        helper.registerAllowedMethod('fileExists', [String], { String path ->
            path.endsWith('ProjectVersion.txt') || path == UNITY_HUB_PATH
        })
        helper.registerAllowedMethod('readFile', [String], { String path -> PROJECT_VERSION_FILE })
        helper.registerAllowedMethod('dir', [String, Closure], { String path, Closure body -> body.call() })
        helper.registerAllowedMethod('libraryResource', [String], { String name -> "// " + name })
        helper.registerAllowedMethod('echo', [String], { String message -> echoed.add(message) })
        helper.registerAllowedMethod('writeFile', [Map], { Map args -> writtenFiles.put(args.file, args.text) })
        helper.registerAllowedMethod('writeJSON', [Map], { Map args ->
            if (args.returnText) {
                return groovy.json.JsonOutput.toJson(args.json)
            }
            writtenJson.put(args.file, args.json)
            return null
        })
        helper.registerAllowedMethod('withCredentials', [List, Closure], { List bindings, Closure body ->
            credentialBindings.addAll(bindings)
            for (binding in bindings) {
                pipelineEnv.put(binding.variable as String, '/secrets/' + binding.credentialsId)
            }
            insideCredentials = true
            try {
                body.call()
            } finally {
                insideCredentials = false
                for (binding in bindings) {
                    pipelineEnv.remove(binding.variable as String)
                }
            }
        })

        unityBuilder = loadScript('unityBuilder.groovy')
        unityBuilder.binding.setVariable('env', pipelineEnv)
        unityBuilder.binding.setVariable('log', asStub([
                info : { Object message -> },
                warn : { Object message -> },
                error: { Object message -> },
        ]))
        unityBuilder.binding.setVariable('file', asStub([
                deleteFile: { String path -> deletedFiles.add(path); return true },
        ]))
        unityBuilder.binding.setVariable('unityHub', asStub([
                init               : { Object path -> },
                getUnityPath       : { Object version, Object revision, Object install -> 'C:/Unity/Editor/Unity.exe' },
                installUnityModules: { Object version, Object modules -> },
        ]))
        unityBuilder.binding.setVariable('unity', asStub([
                init   : { Object path -> },
                execute: { Map args ->
                    unityExecuteArgs = args
                    unityRanInsideCredentials = insideCredentials
                },
        ]))
    }

    private static Expando asStub(Map members) {
        def stub = new Expando()
        for (entry in members) {
            stub.setProperty(entry.key as String, entry.value)
        }
        return stub
    }

    private static Object jobScript(Map options, Map env = [:]) {
        def script = new Expando()
        script.options = options
        script.env = env
        return script
    }

    private Map runBuild(Map options, Map env = [:]) {
        unityBuilder.build(jobScript([unityHubPath: UNITY_HUB_PATH] + options, env))
        return writtenJson.get('ci_build_options.json') as Map
    }

    private Map runAndroidBuild(Map android) {
        return runBuild([
                buildTarget    : 'Android',
                buildOutputPath: 'Build',
                buildTag       : 'gacha-42',
                android        : android,
        ], [BUILD_NUMBER: '128'])
    }

    @Test
    void 'the jenkins build number reaches the unity build options'() {
        def buildOptions = runBuild(
                [buildTarget: 'WebGL', buildOutputPath: 'Build/WebGL'],
                [BUILD_NUMBER: '128'])

        assertEquals(128, buildOptions.buildNumber as int)
    }

    @Test
    void 'an explicit build number in the job options wins over the jenkins counter'() {
        def buildOptions = runBuild(
                [buildTarget: 'WebGL', buildOutputPath: 'Build/WebGL', buildNumber: 7],
                [BUILD_NUMBER: '128'])

        assertEquals(7, buildOptions.buildNumber as int)
    }

    @Test
    void 'hideUnityLogo false reaches the unity build options instead of being dropped as falsy'() {
        def buildOptions = runBuild(
                [buildTarget: 'WebGL', buildOutputPath: 'Build/WebGL', hideUnityLogo: false],
                [BUILD_NUMBER: '128'])

        assertTrue('hideUnityLogo must be present, otherwise the C# default of true wins',
                buildOptions.containsKey('hideUnityLogo'))
        assertFalse(buildOptions.hideUnityLogo as boolean)
    }

    @Test
    void 'the options json is echoed without the keystore passwords'() {
        runAndroidBuild([keystoreName: 'user.keystore', keystorePass: 'store-secret', keyaliasPass: 'alias-secret'])

        def printed = echoed.join('\n')
        assertFalse('the keystore password must not reach the job console', printed.contains('store-secret'))
        assertFalse('the key alias password must not reach the job console', printed.contains('alias-secret'))
        assertTrue(printed.contains('user.keystore'))
    }

    @Test
    void 'android options without credential ids are passed through unchanged'() {
        def buildOptions = runAndroidBuild([
                buildAppBundle: false,
                keystoreName  : 'user.keystore',
                keystorePass  : 'store-secret',
                keyaliasName  : 'gacha',
                keyaliasPass  : 'alias-secret',
        ])

        assertEquals('user.keystore', buildOptions.android.keystoreName)
        assertEquals('store-secret', buildOptions.android.keystorePass)
        assertEquals('gacha', buildOptions.android.keyaliasName)
        assertEquals('alias-secret', buildOptions.android.keyaliasPass)
        assertTrue('no credential binding may be opened when no ids are given', credentialBindings.isEmpty())
    }

    @Test
    void 'a keystore file credential provides the keystore path'() {
        def buildOptions = runAndroidBuild([keyaliasName: 'gacha', keystoreCredentialsId: 'gacha-keystore'])

        assertEquals('/secrets/gacha-keystore', buildOptions.android.keystoreName)
    }

    @Test
    void 'the keystore and alias passwords come from string credentials'() {
        def buildOptions = runAndroidBuild([
                keyaliasName             : 'gacha',
                keystorePassCredentialsId: 'gacha-keystore-pass',
                keyaliasPassCredentialsId: 'gacha-keyalias-pass',
        ])

        assertEquals('/secrets/gacha-keystore-pass', buildOptions.android.keystorePass)
        assertEquals('/secrets/gacha-keyalias-pass', buildOptions.android.keyaliasPass)
    }

    @Test
    void 'a credential wins over a plain value for the same field'() {
        def buildOptions = runAndroidBuild([
                keystorePass             : 'plain-secret',
                keystorePassCredentialsId: 'gacha-keystore-pass',
        ])

        assertEquals('/secrets/gacha-keystore-pass', buildOptions.android.keystorePass)
    }

    @Test
    void 'the keystore is bound as a file credential and the passwords as string credentials'() {
        runAndroidBuild([
                keystoreCredentialsId    : 'gacha-keystore',
                keystorePassCredentialsId: 'gacha-keystore-pass',
                keyaliasPassCredentialsId: 'gacha-keyalias-pass',
        ])

        def bindingClassById = [:]
        for (binding in credentialBindings) {
            bindingClassById.put(binding.credentialsId, binding['$class'])
        }
        assertEquals('FileBinding', bindingClassById['gacha-keystore'])
        assertEquals('StringBinding', bindingClassById['gacha-keystore-pass'])
        assertEquals('StringBinding', bindingClassById['gacha-keyalias-pass'])
    }

    @Test
    void 'credential ids never reach the options handed to Unity'() {
        def buildOptions = runAndroidBuild([
                keystoreCredentialsId    : 'gacha-keystore',
                keystorePassCredentialsId: 'gacha-keystore-pass',
                keyaliasPassCredentialsId: 'gacha-keyalias-pass',
        ])

        assertFalse(buildOptions.android.containsKey('keystoreCredentialsId'))
        assertFalse(buildOptions.android.containsKey('keystorePassCredentialsId'))
        assertFalse(buildOptions.android.containsKey('keyaliasPassCredentialsId'))
    }

    @Test
    void 'unity runs inside the credentials block, because a file credential dies with it'() {
        runAndroidBuild([keystoreCredentialsId: 'gacha-keystore'])

        assertTrue(unityRanInsideCredentials)
    }

    @Test
    void 'the options file is removed after a build that resolved credentials'() {
        runAndroidBuild([keystoreCredentialsId: 'gacha-keystore'])

        assertTrue('the options file holds the resolved passwords', deletedFiles.contains('ci_build_options.json'))
    }

    @Test
    void 'the options file is left alone when no credentials were used'() {
        runBuild([buildTarget: 'WebGL', buildOutputPath: 'Build/WebGL'], [BUILD_NUMBER: '128'])

        assertFalse(deletedFiles.contains('ci_build_options.json'))
    }

    @Test
    void 'the keystore passwords still reach unity through the options file'() {
        def buildOptions = runAndroidBuild([keystorePass: 'store-secret'])

        assertEquals('store-secret', buildOptions.android.keystorePass)
    }

    @Test
    void 'android artifact is an aab when the app bundle flag is set on the android options'() {
        def path = unityBuilder.getLocationPathName(jobScript([
                buildTarget    : 'Android',
                buildOutputPath: 'Build',
                buildTag       : 'gacha-42',
                android        : [buildAppBundle: true],
        ]))

        assertEquals('Build/gacha-42.aab', path.toString())
    }

    @Test
    void 'android artifact stays an apk without the app bundle flag'() {
        def path = unityBuilder.getLocationPathName(jobScript([
                buildTarget    : 'Android',
                buildOutputPath: 'Build',
                buildTag       : 'gacha-42',
                android        : [buildAppBundle: false],
        ]))

        assertEquals('Build/gacha-42.apk', path.toString())
    }

    @Test
    void 'unity is invoked with the builder entry point and the options file'() {
        runBuild([buildTarget: 'WebGL', buildOutputPath: 'Build/WebGL'], [BUILD_NUMBER: '128'])

        assertEquals('JenkinsBuilder.Build', unityExecuteArgs.methodToExecute)
        assertTrue(unityExecuteArgs.additionalParameters.toString().contains('-ciOptionsFile ci_build_options.json'))
    }
}
