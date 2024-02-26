@Library('unity') _

options = [
        autoDetectUnityVersion: true,
        buildTarget: 'StandaloneWindows64',
        buildOutputPath: 'Build/JackWin64',
        executableName: 'jack',
        webhookUrl: 'https://webhook.mi8820.ru/hooks/deploy-desktop',
        webhookCredentials: 'webhook_mi8820',
        discordWebhookUrl: 'https://discord.com/api/webhooks/1210183242733588501/a_Wiw49VlKo6UA9Rp158cfJQgdujVO0tmfDABuMj2-WcdAwTt8zSZ_KVQCKDl_ABeIRW',
        notifyStages: ['Queued', 'Started', 'Canceled', 'Failed', 'Success'],
        nodeLabel: 'unity'
]

scm = scmGit(
        branches: [[name: 'master']],
        extensions: [lfs()],
        userRemoteConfigs: [[url: 'https://github.com/Seven-Winds-Studio/MadJack.git', credentialsId: 'github']]
)

unityCi(this)