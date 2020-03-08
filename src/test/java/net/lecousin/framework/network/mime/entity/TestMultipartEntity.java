package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.mail.internet.MimeMultipart;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Threading;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOAsInputStream;
import net.lecousin.framework.io.IOFromInputStream;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.io.buffering.SimpleBufferedReadable;
import net.lecousin.framework.network.mime.MimeUtil;
import net.lecousin.framework.network.mime.header.InternetAddressListHeaderValue;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.text.CharArrayStringBuffer;

import org.junit.Assert;
import org.junit.Test;

public class TestMultipartEntity extends LCCoreAbstractTest {
	
	@Test
	public void testParseMultipart() throws Exception {
		testParseMultipart("multipart1.raw");
	}

	@Test
	public void testParseMultipartWithoutLeadingCRLF() throws Exception {
		testParseMultipart("multipart2.raw");
	}
	
	private void testParseMultipart(String filename) throws Exception {
		try (IOFromInputStream body = new IOFromInputStream(this.getClass().getClassLoader().getResourceAsStream(filename), filename, Threading.getCPUTaskManager(), Task.Priority.NORMAL)) {
			testParseMultipart(body.createProducer(false));
		}
		try (IOFromInputStream body = new IOFromInputStream(this.getClass().getClassLoader().getResourceAsStream(filename), filename, Threading.getCPUTaskManager(), Task.Priority.NORMAL)) {
			testParseMultipart(body.createProducer(8, false, false));
		}
	}
	
	private static void testParseMultipart(AsyncProducer<ByteBuffer, IOException> producer) throws Exception {
		MultipartEntity entity = new MultipartEntity("---------------------------114772229410704779042051621609".getBytes(), "form-data");
		entity.setPartFactory(DefaultMimeEntityFactory.getInstance());
		producer.toConsumer(entity.createConsumer(null), "Parse MIME", Task.Priority.NORMAL).blockThrow(0);
		Assert.assertEquals(5, entity.getParts().size());
		Assert.assertEquals(3, entity.getPartsOfType(BinaryEntity.class).size());
		for (MimeEntity p : entity.getParts()) {
			ParameterizedHeaderValue dispo = p.getHeaders().getFirstValue(MimeHeaders.CONTENT_DISPOSITION, ParameterizedHeaderValue.class);
			Assert.assertEquals("form-data", dispo.getMainValue());
			String name = dispo.getParameter("name");
			if ("name".equals(name)) {
				Assert.assertEquals("AJ ONeal", IOUtil.readFullyAsStringSync(((BinaryEntity)p).getContent(), StandardCharsets.US_ASCII));
			} else if ("email".equals(name)) {
				Assert.assertEquals("coolaj86@gmail.com", IOUtil.readFullyAsStringSync(((BinaryEntity)p).getContent(), StandardCharsets.US_ASCII));
			} else if ("avatar".equals(name)) {
				// png
				Assert.assertEquals("image/png", p.getHeaders().getContentTypeValue());
			} else if ("attachments[]".equals(name)) {
				// text
				Assert.assertEquals("text/plain", p.getHeaders().getContentTypeValue());
			} else
				throw new AssertionError("Unexpected multipart form-data name " + name);
		}
	}
	
	@Test
	public void testGenerateMailWithMultipart() throws Exception {
		MultipartEntity mailText = new MultipartEntity("alternative");
		mailText.add(new TextEntity("Hello tester", StandardCharsets.UTF_8, "text/plain"));
		mailText.add(new TextEntity("<html><body>Hello tester</body></html>", StandardCharsets.UTF_8, "text/html"));
		mailText.getHeaders().addRawValue("Subject", "This is a test");
		ByteBuffersIO out = new ByteBuffersIO(false, "Mail", Task.Priority.NORMAL);
		AsyncSupplier<Long, IOException> copy = IOUtil.copy(mailText.writeEntity().blockResult(0), out, -1, false, null, 0);
		copy.blockThrow(0);
		out.seekSync(SeekType.FROM_BEGINNING, 0);
		String s = IOUtil.readFullyAsStringSync(out, StandardCharsets.UTF_8);
		System.out.println("_____________ Start of Mail ___________");
		System.out.println(s);
		System.out.println("_____________ End of Mail ___________");
		out.seekSync(SeekType.FROM_BEGINNING, 0);
		javax.mail.Session session = javax.mail.Session.getInstance(new Properties());
		javax.mail.internet.MimeMessage mail = new javax.mail.internet.MimeMessage(session, IOAsInputStream.get(out, false));
		Assert.assertEquals("This is a test", mail.getSubject());
		Object content = mail.getContent();
		Assert.assertTrue(content instanceof MimeMultipart);
		MimeMultipart m = (MimeMultipart)content;
		Assert.assertTrue("Final boundary not found", m.isComplete());
		Assert.assertEquals(2, m.getCount());
		Assert.assertEquals("Hello tester", m.getBodyPart(0).getContent());
		Assert.assertEquals("<html><body>Hello tester</body></html>", m.getBodyPart(1).getContent());
		out.close();
	}
	
	@Test
	public void testParseEML() throws Exception {
		MimeEntity mime = MimeEntity.parse(new SimpleBufferedReadable(new IOFromInputStream(this.getClass().getClassLoader().getResourceAsStream("html-attachment-encoded-filename.eml"), "html-attachment-encoded-filename.eml", Threading.getCPUTaskManager(), Task.Priority.NORMAL), 4096), DefaultMimeEntityFactory.getInstance()).blockResult(0);
		Assert.assertTrue(mime instanceof MultipartEntity);
		MultipartEntity eml = (MultipartEntity)mime;
		Assert.assertEquals(2, eml.getParts().size());

		// part 1 is the mail with alternative plain and html
		MimeEntity part = eml.getParts().get(0);
		Assert.assertTrue(part instanceof MultipartEntity);
		MultipartEntity textAlt = (MultipartEntity)part;
		Assert.assertEquals(2, textAlt.getParts().size());
		MimeEntity textPart = textAlt.getParts().get(0);
		Assert.assertTrue(textPart instanceof TextEntity);
		TextEntity text = (TextEntity)textPart;
		Assert.assertEquals("Your email client does not support HTML emails", text.getText());
		textPart = textAlt.getParts().get(1);
		Assert.assertTrue(textPart instanceof TextEntity);
		text = (TextEntity)textPart;
		Assert.assertEquals("<html>Test Message<html>", text.getText());
		
		// part 2 is the email attachment
		part = eml.getParts().get(1);
		ParameterizedHeaderValue dispo = part.getHeaders().getFirstValue(MimeHeaders.CONTENT_DISPOSITION, ParameterizedHeaderValue.class);
		Assert.assertNotNull(dispo);
		Assert.assertEquals("Test_Attachment_-_a>ä,_o>ö,_u>ü,_au>äu", dispo.getParameter("filename"));
		Assert.assertEquals("test", MimeUtil.decodeRFC2047(part.getHeaders().getFirstRawValue("Test-B64")));
		
		Assert.assertEquals(2, eml.getHeaders().getList("Received").size());
		Assert.assertEquals(1, eml.getHeaders().getList("From").size());
		Assert.assertTrue(eml.getHeaders().has("To"));
		Assert.assertEquals(1, eml.getHeaders().getValues("To", InternetAddressListHeaderValue.class).size());
		InternetAddressListHeaderValue ccList = eml.getHeaders().getFirstValue("Cc", InternetAddressListHeaderValue.class);
		Assert.assertEquals(3, ccList.getAddresses().size());
		Assert.assertEquals("Test1", ccList.getAddresses().get(0).getDisplayName());
		Assert.assertEquals("test1@lecousin.net", ccList.getAddresses().get(0).getAddress());
		Assert.assertEquals("Test2", ccList.getAddresses().get(1).getDisplayName());
		Assert.assertEquals("test2@lecousin.net", ccList.getAddresses().get(1).getAddress());
		Assert.assertEquals("Test Three", ccList.getAddresses().get(2).getDisplayName());
		Assert.assertEquals("test3@lecousin.net", ccList.getAddresses().get(2).getAddress());
		
		Assert.assertNull(eml.getHeaders().getContentLength());
		eml.getHeaders().appendTo(new CharArrayStringBuffer());
	}

}
