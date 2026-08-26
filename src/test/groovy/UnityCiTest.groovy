import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.assertEquals

class UnityCiTest extends BasePipelineTest {

    private Object unityCi

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots = ['vars'] as String[]
        scriptExtension = 'groovy'
        super.setUp()
        unityCi = loadScript('unityCi.groovy')
    }

    private static Object jobScript(Map options, Map env = [:]) {
        def script = new Expando()
        script.options = options
        script.env = env
        return script
    }

    @Test
    void 'the node label from the job options wins'() {
        assertEquals('mac', unityCi.getNodeLabel(jobScript([nodeLabel: 'mac'], [NODE_LABEL: 'unity-win'])))
    }

    @Test
    void 'the node label falls back to NODE_LABEL from the environment'() {
        assertEquals('unity-win', unityCi.getNodeLabel(jobScript([:], [NODE_LABEL: 'unity-win'])))
    }

    @Test
    void 'the node label falls back to unity when nothing is configured'() {
        assertEquals('unity', unityCi.getNodeLabel(jobScript([:], [:])))
    }
}
