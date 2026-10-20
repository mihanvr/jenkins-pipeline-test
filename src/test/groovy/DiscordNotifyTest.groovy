import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.*

// Проверяет JSON уведомления через настоящий шаг общей библиотеки.
class DiscordNotifyTest {
    // Загруженный production-шаг общей библиотеки Jenkins.
    private Script unityCi
    // Запросы сохраняются вместо отправки в Discord.
    private List<Map> requests

    // Подменяет только внешние шаги Jenkins, не исполняя тело vars-скрипта.
    @Before
    void setUp() {
        requests = []
        def loader = new GroovyClassLoader(getClass().classLoader)
        loader.parseClass('@interface NonCPS {}')
        // Эти типы нужны для компиляции соседних методов, уведомление их не вызывает.
        loader.parseClass('package hudson; class BulkChange {}')
        loader.parseClass('class ParametersDefinitionProperty {}')
        def binding = new Binding([
                echo: { Object message -> },
                writeJSON: { Map args -> JsonOutput.toJson(args.json) },
                httpRequest: { Map args -> requests.add(args) },
        ])
        unityCi = new GroovyShell(loader, binding).parse(new File('vars/unityCi.groovy'))
    }

    // Передаёт архивы с теми же свойствами, которые читает Jenkins Run.Artifact.
    private Map job(String platform, List<Map> artifacts) {
        return [
                options: [notifyStages: ['Success'], discordWebhookUrl: 'https://discord.invalid/webhook', buildTarget: platform],
                scm: [arguments: [branches: [[name: 'master']]]],
                env: [BUILD_URL: 'https://ci.invalid/job/game/42/', JOB_URL: 'https://ci.invalid/job/game/',
                      JOB_NAME: 'game', BUILD_NUMBER: '42', GIT_COMMIT: 'abcdef012345', BUILD_NODE_NAME: 'builder'],
                currentBuild: [buildCauses: [[shortDescription: 'Started by developer']], rawBuild: [artifacts: artifacts]],
        ]
    }

    // Возвращает сериализованный результат настоящего discordNotify.
    private Map notifyJob(Map script) {
        unityCi.discordNotify(script: script, buildStatus: 'Success', content: 'Build ready')
        assertEquals(1, requests.size())
        assertEquals('POST', requests[0].httpMode)
        assertEquals('APPLICATION_JSON', requests[0].contentType)
        assertEquals('https://discord.invalid/webhook', requests[0].url)
        return new JsonSlurper().parseText(requests[0].requestBody as String) as Map
    }

    // Утеря второго архива или его размера должна менять видимый JSON.
    @Test
    void 'every WebGL and Android download shows its archived size'() {
        def fixtures = [
                [platform: 'WebGL', artifacts: [
                        [fileName: 'web.zip', relativePath: 'Build/web.zip', fileSize: 1572864L],
                        [fileName: 'empty.zip', relativePath: 'Build/empty.zip', fileSize: 0L],
                ], expected: [
                        '[web.zip](https://ci.invalid/job/game/42/artifact/Build/web.zip) (1 MB)',
                        '[empty.zip](https://ci.invalid/job/game/42/artifact/Build/empty.zip) (0 B)',
                ]],
                [platform: 'Android', artifacts: [
                        [fileName: 'game.apk', relativePath: 'Build/game.apk', fileSize: 1536L],
                        [fileName: 'game.aab', relativePath: 'Build/game.aab', fileSize: 2048L],
                ], expected: [
                        '[game.apk](https://ci.invalid/job/game/42/artifact/Build/game.apk) (1 KB)',
                        '[game.aab](https://ci.invalid/job/game/42/artifact/Build/game.aab) (2 KB)',
                ]],
        ]
        fixtures.each { fixture ->
            requests.clear()
            def payload = notifyJob(job(fixture.platform, fixture.artifacts))
            def fields = payload.embeds[0].fields
            assertEquals(fixture.expected, fields.findAll { it.name == 'Download' }*.value)
            assertEquals(fixture.platform, fields.find { it.name == 'Platform' }.value)
            assertEquals('Build ready', payload.content)
            assertEquals(42837, payload.embeds[0].color)
            assertEquals(['Build Success', 'Job', 'Platform', 'Started By', 'Branch', 'Commit', 'Node'],
                    fields.findAll { it.name != 'Download' }*.name)
        }
    }

    // Размер за границей int не должен прерывать отправку уведомления.
    @Test
    void 'artifacts larger than two GiB still produce a notification'() {
        def payload = notifyJob(job('Android', [[fileName: 'large.aab', relativePath: 'large.aab', fileSize: 3221225472L]]))
        assertEquals('[large.aab](https://ci.invalid/job/game/42/artifact/large.aab) (3072 MB)',
                payload.embeds[0].fields.find { it.name == 'Download' }.value)
    }

    // До архивации сохраняются обычные поля уведомления без пустых ссылок.
    @Test
    void 'a build without artifacts retains its notification metadata'() {
        def payload = notifyJob(job('WebGL', []))
        assertEquals(['Build Success', 'Job', 'Platform', 'Started By', 'Branch', 'Commit', 'Node'],
                payload.embeds[0].fields*.name)
        assertEquals('developer', payload.embeds[0].fields.find { it.name == 'Started By' }.value)
        assertEquals('abcdef0', payload.embeds[0].fields.find { it.name == 'Commit' }.value)
    }

    // Переполнение полей или текста оставляет рабочую ссылку на полный список архивов.
    @Test
    void 'large artifact lists respect Discord embed limits'() {
        def fixtures = [
                (1..30).collect { [fileName: "game-${it}.apk", relativePath: "game-${it}.apk", fileSize: 1024L] },
                [[fileName: 'long.apk', relativePath: ('x' * 1100) + '.apk', fileSize: 1024L]],
                (1..10).collect { [fileName: "game-${it}.apk", relativePath: ('x' * 700) + "${it}.apk", fileSize: 1024L] },
        ]
        fixtures.each { artifacts ->
            requests.clear()
            def payload = notifyJob(job('Android', artifacts))
            def fields = payload.embeds[0].fields
            assertTrue(fields.size() <= 25)
            assertTrue(fields.every { it.value.length() <= 1024 })
            assertTrue(fields.sum { it.name.length() + it.value.length() } <= 6000)
            assertTrue(fields.any { it.value == '[All artifacts](https://ci.invalid/job/game/42/artifact/)' })
        }
    }

    // Отключённые уведомления не должны обращаться к HTTP даже при наличии архивов.
    @Test
    void 'notification stage and webhook configuration remain respected'() {
        def disabledStage = job('WebGL', [])
        disabledStage.options.notifyStages = ['Failed']
        def absentWebhook = job('Android', [])
        absentWebhook.options.remove('discordWebhookUrl')
        [disabledStage, absentWebhook].each { script ->
            unityCi.discordNotify(script: script, buildStatus: 'Success')
        }
        assertTrue(requests.isEmpty())
    }

    // Общий размер остаётся десятичной строкой байтов для стороннего webhook.
    @Test
    void 'artifact metadata keeps the webhook schema and long byte sizes'() {
        def script = job('Android', [[fileName: 'large.aab', relativePath: 'Build/large.aab', fileSize: 3221225472L]])
        def json = JsonOutput.toJson(unityCi.getBuildArtifacts(script))
        assertEquals([[size: '3221225472', name: 'large.aab', href: 'https://ci.invalid/job/game/42/artifact/Build/large.aab']],
                new JsonSlurper().parseText(json))
    }
}
