@Library('unity') _

def keystore

withCredentials([string(credentialsId: 'creds', variable: 'jsonText')]) {
    keystore = readJSON text: jsonText
}
options = [
        autoDetectUnityVersion: true,
        buildTarget: 'Android',
        buildOutputPath: 'Build',
        android: [
                buildAppBundle: false,
                keystoreName: 'user.keystore',
                keystorePass: keystore.keystorePass,
                keyaliasName: keystore.keyaliasName,
                keyaliasPass: keystore.keyaliasPass
        ],
        discordWebhookUrl: 'https://discord.com/api/webhooks/**',
        notifyStages: ['Queued', 'Started', 'Canceled', 'Failed', 'Success'],
        nodeLabel: 'unity'
]

scm = scmGit(
        branches: [[name: 'fabric_master']],
        extensions: [lfs(), [$class: 'SubmoduleOption',
                             disableSubmodules: false,
                             parentCredentials: true,
                             recursiveSubmodules: true]],
        userRemoteConfigs: [[url: 'https://github.com/*.git', credentialsId: 'github']]
)

unityCi(this)
