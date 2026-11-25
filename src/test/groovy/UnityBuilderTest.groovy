import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.*

// Проверяет подготовку редактора через тот же шаг, который вызывает unityCi.
class UnityBuilderTest {
    // Требуемая версия из ProjectVersion.txt проекта сборки.
    private static final String VERSION = '6000.6.10f1'
    // Точный changeset передаётся при установке отсутствующего редактора.
    private static final String REVISION = 'a1b2c3d4e5f6'
    // Пробелы в пути проверяют экранирование Windows Editor.
    private static final String WIN_EDITOR = 'C:\\Unity Editors\\6000.6.10f1\\Editor\\Unity.exe'
    // CLI на macOS возвращает путь к приложению редактора.
    private static final String MAC_EDITOR = '/Volumes/Unity Editors/6000.6.10f1/Unity.app'
    // Явно настроенный путь старого Hub.
    private static final String HUB = 'C:\\Unity Hub 3\\Unity Hub.exe'

    // Настоящий Pipeline-скрипт с подменёнными шагами Jenkins.
    private Script builder
    // Доступные файлы и каталоги текущей ноды.
    private Set<String> files
    // Обращения к оболочке на ноде.
    private List<Map> commands
    // Ответы CLI и Hub для текущего сценария.
    private Closure shell
    // Выбирает оболочку Windows или Unix в реальном шаге exec.
    private boolean unix
    // Настройки ноды, видимые Jenkins через env.
    private Map environment

    // Загружает vars без запуска их верхнеуровневого кода.
    @Before
    void setUp() {
        files = ['.', './ProjectSettings/ProjectVersion.txt'] as Set
        commands = []
        unix = false
        environment = [:]
        shell = { Map args -> throw new AssertionError("Unexpected command: ${args}") }
        def binding = new Binding([
                isUnix: { -> unix },
                sh: { Map args -> runShell(args) },
                bat: { Map args -> runShell(args) },
                powershell: { Map args -> runShell(args) },
                fileExists: { String path -> files.contains(path) },
                readFile: { String path -> "m_EditorVersionWithRevision: ${VERSION} (${REVISION})" },
                readJSON: { Map args -> new JsonSlurper().parseText(args.text as String) },
                writeJSON: { Map args -> '' },
                writeFile: { Map args -> },
                libraryResource: { String path -> '' },
                dir: { String path, Closure body -> body() },
                echo: { Object message -> },
                error: { String message -> throw new IllegalStateException(message) },
        ])
        def groovy = new GroovyShell(binding)
        ['exec', 'log', 'unityHub', 'unityCli', 'unity'].each { name ->
            binding.setVariable(name, groovy.parse(new File("vars/${name}.groovy")))
        }
        builder = groovy.parse(new File('vars/unityBuilder.groovy'))
    }

    // Явный путь CLI приоритетнее переменной ноды и настройки старого Hub.
    @Test
    void 'configured Windows CLI finds exact version and skips installed WebGL support'() {
        def cli = 'D:\\Unity %CLI_HOME%\\unity.exe'
        environment = [UNITY_CLI_PATH: 'D:\\wrong-cli.exe', UNITY_HUB_PATH: HUB]
        files.addAll([cli, WIN_EDITOR, winPlaybackEngine('WebGLSupport')])
        shell = { Map args ->
            assertEquals('@"D:\\Unity %%CLI_HOME%%\\unity.exe" editors --installed --json --no-banner --non-interactive', args.script)
            assertTrue(args.returnStdout)
            assertEquals('UTF-8', args.encoding)
            return inventory([version: "${VERSION}0", location: 'D:\\Wrong\\Unity.exe'],
                    [version: VERSION, location: WIN_EDITOR])
        }

        assertEquals([outputPath: 'Build/Test'], build('WebGL', [unityCliPath: cli]))
        assertEquals(2, commands.size())
        assertTrue(commands.last().script.startsWith("CALL \"${WIN_EDITOR}\" "))
        assertTrue(commands.last().script.contains('-executeMethod JenkinsBuilder.Build -buildTarget WebGL'))
    }

    // Повторный опрос берёт фактический путь только что установленного приложения macOS.
    @Test
    void 'macOS home CLI installs missing editor by changeset and then iOS support'() {
        unix = true
        environment.HOME = '/Users/CI Agent'
        def cli = '/Users/CI Agent/.unity/bin/unity'
        files.add(cli)
        shell = { Map args ->
            assertTrue(args.script.startsWith("'${cli}' "))
            if (args.script.contains(' editors ')) {
                return files.contains(MAC_EDITOR) ? inventory([version: VERSION, location: MAC_EDITOR]) : inventory()
            }
            assertTrue(args.returnStatus)
            if (args.script.contains(' install ')) {
                assertTrue(args.script.contains("install ${VERSION} --changeset ${REVISION} --non-interactive --yes"))
                files.addAll([MAC_EDITOR, "${MAC_EDITOR}/Contents/MacOS/Unity".toString()])
            } else {
                assertTrue(args.script.contains("install-modules --editor-version ${VERSION} -m ios --child-modules --non-interactive --yes"))
                files.add('/Volumes/Unity Editors/6000.6.10f1/PlaybackEngines/iOSSupport')
            }
            return 0
        }

        build('iOS')
        assertEquals(5, commands.size())
        assertTrue(commands.last().script.startsWith("\"${MAC_EDITOR}/Contents/MacOS/Unity\" "))
    }

    // Устанавливает дочерние инструменты для архитектуры найденного Android Editor.
    @Test
    void 'Windows CLI on PATH finishes a partial Android installation'() {
        def cli = 'D:\\Unity Tools\\unity.exe'
        def android = winPlaybackEngine('AndroidPlayer')
        files.addAll([WIN_EDITOR, android, "${android}\\SDK".toString(), "${android}\\NDK".toString()])
        shell = { Map args ->
            if (args.label == 'Find Unity CLI on PATH') return "${cli}\r\n"
            assertTrue(args.script.startsWith("@\"${cli}\" "))
            if (args.script.contains(' editors ')) {
                return inventory([version: VERSION, architecture: 'x86_64', location: WIN_EDITOR],
                        [version: VERSION, architecture: 'arm64', location: 'D:\\Unity ARM\\Editor\\Unity.exe'])
            }
            assertTrue(args.script.contains("install-modules --editor-version ${VERSION} --architecture x86_64 -m android --child-modules --non-interactive --yes"))
            assertTrue(args.returnStatus)
            files.add("${android}\\OpenJDK".toString())
            return 0
        }

        build('Android')
        assertEquals(4, commands.size())
    }

    // Windows Editor уже содержит поддержку своей платформы.
    @Test
    void 'Windows local CLI skips the built-in Windows support'() {
        environment.LOCALAPPDATA = 'C:\\Users\\CI Agent\\AppData\\Local'
        def cli = "${environment.LOCALAPPDATA}\\Unity\\bin\\unity.exe".toString()
        files.addAll([cli, WIN_EDITOR, winPlaybackEngine('WindowsStandaloneSupport')])
        shell = { Map args ->
            assertTrue(args.script.startsWith("@\"${cli}\" editors "))
            return inventory([version: VERSION, location: WIN_EDITOR])
        }

        build('StandaloneWindows64')
        assertEquals(2, commands.size())
    }

    // Unity.exe из каталога Editor в PATH не заменяет отдельный CLI.
    @Test
    void 'editor only on Windows PATH keeps the configured Hub and Linux Mono module'() {
        environment.UNITY_HUB_PATH = HUB
        files.addAll([HUB, WIN_EDITOR])
        shell = { Map args ->
            if (args.label == 'Find Unity CLI on PATH') {
                assertTrue(args.returnStdout)
                assertTrue(args.script.contains('Get-Command unity.exe'))
                assertTrue(args.script.contains(".FileDescription -eq 'unity'"))
                return ''
            }
            assertTrue(args.script.startsWith("\"${HUB}\" -- --headless "))
            if (args.script.contains('editors -i')) return "${VERSION} installed at ${WIN_EDITOR}\r\n"
            assertTrue(args.script.contains("install-modules --version ${VERSION} -m linux-mono --cm"))
            return 0
        }

        build('StandaloneLinux64')
        assertEquals(4, commands.size())
    }

    // Ненайденную версию после установки нельзя передавать в вызов Unity.
    @Test
    void 'editor installation without a matching inventory item stops the build'() {
        environment.UNITY_CLI_PATH = 'D:\\Unity CLI\\unity.exe'
        files.add(environment.UNITY_CLI_PATH)
        shell = { Map args ->
            if (args.script.contains(' editors ')) return inventory()
            assertTrue(args.script.contains("install ${VERSION} --changeset ${REVISION} --non-interactive --yes"))
            return 0
        }

        def failure = assertThrows(IllegalStateException) { build('WebGL') }
        assertTrue(failure.message.contains(VERSION))
        assertTrue(failure.message.contains('after installation'))
        assertEquals(3, commands.size())
    }

    // Для Linux Mono сохраняется установка модуля через доступный CLI.
    @Test
    void 'Windows CLI installs Linux Mono support for a Linux build'() {
        environment.UNITY_CLI_PATH = 'D:\\Unity CLI\\unity.exe'
        files.addAll([environment.UNITY_CLI_PATH, WIN_EDITOR])
        shell = { Map args ->
            if (args.script.contains(' editors ')) return inventory([version: VERSION, location: WIN_EDITOR])
            assertTrue(args.script.contains("install-modules --editor-version ${VERSION} -m linux-mono --child-modules --non-interactive --yes"))
            return 0
        }

        build('StandaloneLinux64')
        assertEquals(3, commands.size())
        assertTrue(commands.last().script.contains('-executeMethod JenkinsBuilder.Build -buildTarget StandaloneLinux64'))
    }

    // Ошибка CLI должна остановить сборку даже на ноде, где доступен старый Hub.
    @Test
    void 'failed CLI module installation stops before invoking the editor'() {
        environment.UNITY_CLI_PATH = 'D:\\Unity CLI\\unity.exe'
        environment.UNITY_HUB_PATH = HUB
        files.addAll([environment.UNITY_CLI_PATH, HUB, WIN_EDITOR])
        shell = { Map args ->
            assertFalse(args.script.contains('--headless'))
            if (args.script.contains(' editors ')) return inventory([version: VERSION, location: WIN_EDITOR])
            return 42
        }

        def failure = assertThrows(IllegalStateException) { build('WebGL') }
        assertTrue(failure.message.contains('webgl'))
        assertTrue(failure.message.contains('42'))
        assertEquals(2, commands.size())
    }

    // Даже при коде выхода 0 каталог известного модуля обязан появиться.
    @Test
    void 'successful CLI response without WebGL support stops the build'() {
        environment.UNITY_CLI_PATH = 'D:\\Unity CLI\\unity.exe'
        files.addAll([environment.UNITY_CLI_PATH, WIN_EDITOR])
        shell = { Map args ->
            if (args.script.contains(' editors ')) return inventory([version: VERSION, location: WIN_EDITOR])
            return 0
        }

        def failure = assertThrows(IllegalStateException) { build('WebGL') }
        assertTrue(failure.message.contains('webgl'))
        assertTrue(failure.message.contains(VERSION))
        assertEquals(2, commands.size())
    }

    // Ошибка чтения списка редакторов не должна становиться попыткой установки.
    @Test
    void 'CLI inventory with an error fails discovery'() {
        environment.UNITY_CLI_PATH = 'D:\\Unity CLI\\unity.exe'
        files.add(environment.UNITY_CLI_PATH)
        shell = { Map args -> '{"success":false,"errors":[{"code":"INSTALLATION_ERROR","message":"cannot read inventory"}]}' }

        def failure = assertThrows(IllegalStateException) { build('WebGL') }
        assertTrue(failure.message.contains('cannot read inventory'))
        assertEquals(1, commands.size())
    }

    // Передаёт параметры вызова из unityCi.
    private Map build(String target, Map overrides = [:]) {
        builder.build([options: [buildTarget: target, buildOutputPath: 'Build/Test'] + overrides, env: environment])
    }

    // Возвращает ответы CLI и оставляет сам редактор на границе процесса.
    private Object runShell(Map args) {
        commands.add(args)
        if (args.label == 'Execute Unity Method') return 0
        return shell(args)
    }

    // Формат ответа команды unity editors --installed --json.
    private static String inventory(Map... editors) {
        JsonOutput.toJson([success: true, data: [items: editors.collect { [moduleNames: []] + it }, partial: false], errors: []])
    }

    // Раскладка PlaybackEngines в Windows Editor.
    private static String winPlaybackEngine(String name) {
        "C:\\Unity Editors\\${VERSION}\\Editor\\Data\\PlaybackEngines\\${name}"
    }
}
