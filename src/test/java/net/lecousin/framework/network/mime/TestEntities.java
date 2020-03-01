package net.lecousin.framework.network.mime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Properties;
import java.util.zip.Deflater;

import javax.mail.internet.MimeMultipart;

import net.lecousin.compression.gzip.GZipWritable;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Threading;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOAsInputStream;
import net.lecousin.framework.io.IOFromInputStream;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.io.buffering.SimpleBufferedReadable;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.network.mime.entity.FormDataEntity;
import net.lecousin.framework.network.mime.entity.FormDataEntity.PartFile;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.network.mime.entity.TextEntity;
import net.lecousin.framework.network.mime.header.InternetAddressHeaderValue;
import net.lecousin.framework.network.mime.header.InternetAddressListHeaderValue;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.text.CharArrayStringBuffer;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;

public class TestEntities extends LCCoreAbstractTest {

	@Test
	public void testFormUrlEncodedEntity() throws Exception {
		FormUrlEncodedEntity source;
		source = new FormUrlEncodedEntity();
		source.add("key", "value");
		check(source);
		source = new FormUrlEncodedEntity();
		source.add("key 1", "value 1");
		source.add("key 2", "value 2");
		check(source);
		source = new FormUrlEncodedEntity();
		source.add("key+%@=1", "value=%1");
		source.add("key\"2", "value'2");
		check(source);
	}
	
	private static void check(FormUrlEncodedEntity source) throws Exception {
		// generate
		IO.Readable io = source.writeEntity().blockResult(0);
		// parse
		FormUrlEncodedEntity target = (FormUrlEncodedEntity)MimeEntity.parse(io, DefaultMimeEntityFactory.getInstance()).blockResult(0);
		// check they are the same
		Iterator<Pair<String, String>> itSrc = source.getParameters().iterator();
		Iterator<Pair<String, String>> itTar = target.getParameters().iterator();
		while (itSrc.hasNext()) {
			Assert.assertTrue(itTar.hasNext());
			Pair<String, String> src = itSrc.next();
			Pair<String, String> tar = itTar.next();
			Assert.assertEquals(src.getValue1(), tar.getValue1());
			Assert.assertEquals(src.getValue2(), tar.getValue2());
		}
		Assert.assertFalse(itTar.hasNext());
	}
	
	@Test
	public void testParseFormUrlEncodedEntity() throws Exception {
		MimeEntity mime = MimeEntity.parse(new SimpleBufferedReadable(new IOFromInputStream(this.getClass().getClassLoader().getResourceAsStream("formurlencoded.raw"), "formurlencoded.raw", Threading.getCPUTaskManager(), Task.Priority.NORMAL), 4096), DefaultMimeEntityFactory.getInstance()).blockResult(0);
		Assert.assertTrue(mime instanceof FormUrlEncodedEntity);
		FormUrlEncodedEntity entity = (FormUrlEncodedEntity)mime;
		Assert.assertEquals("Cosby", entity.getParameter("home"));
		Assert.assertEquals("flies", entity.getParameter("favorite flavor"));
	}
	
	@Test
	public void testParseMultipart() throws Exception {
		testParseMultipart("multipart1.raw");
	}

	@Test
	public void testParseMultipartWithoutLeadingCRLF() throws Exception {
		testParseMultipart("multipart2.raw");
	}
	
	private void testParseMultipart(String filename) throws Exception {
		MultipartEntity entity = new MultipartEntity("---------------------------114772229410704779042051621609".getBytes(), "form-data");
		entity.setPartFactory(DefaultMimeEntityFactory.getInstance());
		IOFromInputStream body = new IOFromInputStream(this.getClass().getClassLoader().getResourceAsStream(filename), filename, Threading.getCPUTaskManager(), Task.Priority.NORMAL);
		body.createProducer(false).toConsumer(entity.createConsumer(), "Parse MIME", Task.Priority.NORMAL).blockThrow(0);
		body.close();
		Assert.assertEquals(5, entity.getParts().size());
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
	
	@SuppressWarnings("resource")
	@Test
	public void testFormData() throws Exception {
		FormDataEntity form = new FormDataEntity();
		form.addField("test", "1", StandardCharsets.US_ASCII);
		form.addFile("myfile", "test.html", new ParameterizedHeaderValue("text/html", "charset", "utf-8"), new ByteArrayIO("<html></html>".getBytes(StandardCharsets.UTF_8), "test"));
		ByteArrayIO gz = new ByteArrayIO(1024, "test");
		GZipWritable gzip = new GZipWritable(gz, Task.Priority.NORMAL, Deflater.BEST_COMPRESSION, 3);
		gzip.writeSync(ByteBuffer.wrap("<html><body></body></html>".getBytes(StandardCharsets.UTF_8)));
		gzip.finishSynch();
		gz.seekSync(SeekType.FROM_BEGINNING, 0);
		PartFile f = form.addFile("encoded", "test.html.gz", new ParameterizedHeaderValue("text/html", "charset", "utf-8"), gz);
		f.getHeaders().setRawValue(MimeHeaders.CONTENT_ENCODING, "gzip");
		form.addField("hello", "world", StandardCharsets.UTF_8);
		
		ByteBuffersIO out = new ByteBuffersIO(false, "Form", Task.Priority.NORMAL);
		AsyncSupplier<Long, IOException> copy = IOUtil.copy(form.writeEntity().blockResult(0), out, -1, false, null, 0);
		copy.blockThrow(0);

		out.seekSync(SeekType.FROM_BEGINNING, 0);
		String generated = IOUtil.readFullyAsString(out, StandardCharsets.ISO_8859_1, Task.Priority.NORMAL).blockResult(0).asString();
		System.out.println("_______________ generated FormDataEntity:");
		System.out.println(generated);
		System.out.println("_______________ end of generated FormDataEntity");

		FormDataEntity parse = new FormDataEntity(form.getBoundary());
		out.seekSync(SeekType.FROM_BEGINNING, 0);
		out.createProducer(false).toConsumer(parse.createConsumer(), "Parse MIME", Task.Priority.NORMAL).blockThrow(0);
		Assert.assertEquals(2, parse.getFields().size());
		Assert.assertEquals("1", parse.getFieldValue("test"));
		Assert.assertEquals("world", parse.getFieldValue("hello"));
		PartFile file = parse.getFile("myfile");
		Assert.assertEquals("test.html", file.getFilename());
		String content = IOUtil.readFullyAsStringSync(file.getContent(), StandardCharsets.UTF_8);
		Assert.assertEquals("<html></html>", content);
		file = parse.getFile("encoded");
		Assert.assertEquals("test.html.gz", file.getFilename());
		content = IOUtil.readFullyAsStringSync(file.getContent(), StandardCharsets.UTF_8);
		Assert.assertEquals("<html><body></body></html>", content);
		
		Assert.assertNull(parse.getFile("tutu"));
		Assert.assertNull(parse.getFieldValue("tutu"));
		
		form.close();
		parse.closeAsync();
		out.close();
		gzip.close();
		gz.close();
	}

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
