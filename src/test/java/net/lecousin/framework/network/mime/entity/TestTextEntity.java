package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.network.mime.header.InternetAddressHeaderValue;
import net.lecousin.framework.network.mime.header.InternetAddressListHeaderValue;

import org.junit.Assert;
import org.junit.Test;

public class TestTextEntity extends LCCoreAbstractTest {

	@Test
	public void testTextEntity() throws Exception {
		TextEntity src = new TextEntity("This is a test", StandardCharsets.UTF_8, "text/test");
		src.setText("This is still a text");
		src.setCharset(StandardCharsets.UTF_16);
		Assert.assertEquals("This is still a text", src.getText());
		Assert.assertEquals(StandardCharsets.UTF_16, src.getCharset());
		src.getHeaders().add("MyAddress", new InternetAddressHeaderValue("Myself", "me@domain.org"));
		src.getHeaders().add("Toto", new InternetAddressHeaderValue(null, "toto@domain.org"));
		InternetAddressHeaderValue addr = src.getHeaders().getFirstValue("myaddress", InternetAddressHeaderValue.class);
		addr.setDisplayName("This is myself");
		src.getHeaders().set("myaddress", addr);
		addr = src.getHeaders().getFirstValue("toto", InternetAddressHeaderValue.class);
		addr.setAddress("toto@zero.com");
		src.getHeaders().set("Toto", addr);
		InternetAddressListHeaderValue addresses = new InternetAddressListHeaderValue();
		addresses.addAddress("First", "1@first.net");
		addresses.addAddress(null, "2@second.net");
		src.getHeaders().set("List", addresses);

		ByteBuffersIO out = new ByteBuffersIO(false, "Entity", Task.Priority.NORMAL);
		AsyncSupplier<Long, IOException> copy = IOUtil.copy(src.writeEntity().blockResult(0), out, -1, false, null, 0);
		copy.blockThrow(0);
		out.seekSync(SeekType.FROM_BEGINNING, 0);
		String s = IOUtil.readFullyAsStringSync(out, StandardCharsets.UTF_8);
		System.out.println("_____________ Start of Text Entity ___________");
		System.out.println(s);
		System.out.println("_____________ End of Text Entity ___________");
		out.seekSync(SeekType.FROM_BEGINNING, 0);
		
		MimeEntity mime = MimeEntity.parse(out, DefaultMimeEntityFactory.getInstance()).blockResult(0);
		Assert.assertTrue(mime instanceof TextEntity);
		TextEntity parsed = (TextEntity)mime;
		Assert.assertEquals("This is still a text", parsed.getText());
		Assert.assertEquals(StandardCharsets.UTF_16, parsed.getCharset());
		
		addr = parsed.getHeaders().getFirstValue("MyAddress", InternetAddressHeaderValue.class);
		Assert.assertNotNull(addr);
		Assert.assertEquals("This is myself", addr.getDisplayName());
		Assert.assertEquals("me@domain.org", addr.getAddress());
		addr = parsed.getHeaders().getFirstValue("toto", InternetAddressHeaderValue.class);
		Assert.assertNotNull(addr);
		Assert.assertNull(addr.getDisplayName());
		Assert.assertEquals("toto@zero.com", addr.getAddress());
		addresses = parsed.getHeaders().getFirstValue("list", InternetAddressListHeaderValue.class);
		Assert.assertNotNull(addresses);
		Assert.assertEquals(2, addresses.getAddresses().size());
		Assert.assertEquals("First", addresses.getAddresses().get(0).getDisplayName());
		Assert.assertEquals("1@first.net", addresses.getAddresses().get(0).getAddress());
		Assert.assertNull(addresses.getAddresses().get(1).getDisplayName());
		Assert.assertEquals("2@second.net", addresses.getAddresses().get(1).getAddress());
		
		out.close();
	}
	
	@Test
	public void testRange() throws Exception {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 1000; ++i)
			s.append("Hello World!");
		String text = s.toString();
		EntityTestUtil.testBodyRangeProducer(() -> {
			return new TextEntity(text, StandardCharsets.UTF_8, "text/test");
		});
	}

}
