package net.lecousin.framework.network.mime;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;

import org.junit.Assert;
import org.junit.Test;

public class TestHeader extends LCCoreAbstractTest {

	@Test(timeout=30000)
	public void testMimeHeader() throws Exception {
		MimeHeader h = new MimeHeader("X-Test", "toto; titi=tata; hello=world, heho, aa; bb=cc");
		Assert.assertEquals("X-Test", h.getName());
		Assert.assertEquals("x-test", h.getNameLowerCase());
		Assert.assertEquals("toto; titi=tata; hello=world, heho, aa; bb=cc", h.getRawValue());
		ParameterizedHeaderValues values = h.getValue(ParameterizedHeaderValues.class);
		Assert.assertEquals(3, values.getValues().size());
		ParameterizedHeaderValue v = values.getMainValue("toto");
		Assert.assertNotNull(v);
		Assert.assertEquals(2, v.getParameters().size());
		Assert.assertEquals("tata", v.getParameter("titi"));
		Assert.assertEquals("world", v.getParameter("hello"));
		Assert.assertNull(v.getParameter("heho"));
		Assert.assertNull(v.getParameter("aa"));
		Assert.assertNull(v.getParameter("bb"));
		v = values.getMainValue("heho");
		Assert.assertNotNull(v);
		Assert.assertEquals(0, v.getParameters().size());
		Assert.assertNull(v.getParameter("aa"));
		Assert.assertNull(v.getParameter("bb"));
		v = values.getMainValue("aa");
		Assert.assertNotNull(v);
		Assert.assertEquals(1, v.getParameters().size());
		Assert.assertEquals("cc", v.getParameter("bb"));
		h.setRawValue("hello; fr=bonjour");
		Assert.assertEquals("hello; fr=bonjour", h.getRawValue());
		v = h.getValue(ParameterizedHeaderValue.class);
		Assert.assertEquals("hello", v.getMainValue());
		Assert.assertEquals("bonjour", v.getParameter("fr"));
		h.setValue(new ParameterizedHeaderValue("world", "fr", "monde", "test", "yes"));
		Assert.assertEquals("world;fr=monde;test=yes", h.getRawValue());
	}
	
}
