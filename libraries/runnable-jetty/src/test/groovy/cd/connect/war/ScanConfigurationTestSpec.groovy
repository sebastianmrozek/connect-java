package cd.connect.war

import com.bluetrainsoftware.classpathscanner.ResourceScanListener
import org.eclipse.jetty.webapp.WebAppContext
import spock.lang.Specification


class ScanConfigurationTestSpec extends Specification {

	void "Sanity test morph URL when it is a Development Resources"() {
		given: "we have a configuration scanner and some resources"
			ScanConfiguration scan = new ScanConfiguration()
			File file = new File (this.getClass().getResource("/META-INF/resources").toURI())
			assert file.exists()

		when: "we scan"
			ResourceScanListener.ScanResource res = new ResourceScanListener.ScanResource(null, file, null)
			List<URL> result = scan.morphDevelopmentResource(res)

		then:
			result.first().toString().replaceAll("\\\\", "/").contains("src/test/resources/META-INF/resources")
	}

	void "funky monkey"() {
		given: "we have a configuration scanner and some resources"
			WebAppContext context = new WebAppContext( classLoader: this.class.classLoader )
			ScanConfiguration scan = new ScanConfiguration()

		when: "we scan"
			scan.preConfigure( context )
		then:
			context
	}



}