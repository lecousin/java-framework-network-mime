package net.lecousin.framework.network.mime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.Threading;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOAsInputStream;
import net.lecousin.framework.io.IOFromInputStream;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.network.mime.entity.BinaryMimeEntity;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;

public class TestEntities extends LCCoreAbstractTest {

	@Test(timeout=120000)
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
	
	@SuppressWarnings("resource")
	private static void check(FormUrlEncodedEntity source) throws Exception {
		// generate
		ByteBuffersIO io = generateBody(source);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		// parse
		FormUrlEncodedEntity target = new FormUrlEncodedEntity();
		SynchronizationPoint<IOException> parse = target.parse(io, StandardCharsets.UTF_8);
		parse.blockThrow(0);
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
	
	private static ByteBuffersIO generateBody(MimeEntity entity) throws Exception {
		ByteBuffersIO out = new ByteBuffersIO(false, "MIME entity", Task.PRIORITY_NORMAL);
		AsyncWork<Long, IOException> copy = IOUtil.copy(entity.getReadableStream(), out, -1, false, null, 0);
		copy.blockThrow(0);
		return out;
	}
	
	@Test(timeout=120000)
	public void testParseMultipart() throws Exception {
		testParseMultipart("multipart1.raw");
	}

	@Test(timeout=120000)
	public void testParseMultipartWithoutLeadingCRLF() throws Exception {
		testParseMultipart("multipart2.raw");
	}
	
	private void testParseMultipart(String filename) throws Exception {
		MultipartEntity entity = new MultipartEntity("---------------------------114772229410704779042051621609".getBytes(), "form-data");
		SynchronizationPoint<IOException> parse = entity.parse(new IOFromInputStream(this.getClass().getClassLoader().getResourceAsStream(filename), filename, Threading.getCPUTaskManager(), Task.PRIORITY_NORMAL));
		parse.blockThrow(0);
		Assert.assertEquals(5, entity.getParts().size());
		for (MimeEntity p : entity.getParts()) {
			Pair<String, Map<String,String>> dispo = MIMEUtil.parseParameterizedHeader(p.getHeaderSingleValue("Content-Disposition"));
			Assert.assertEquals("form-data", dispo.getValue1());
			String name = dispo.getValue2().get("name");
			if ("name".equals(name)) {
				Assert.assertEquals("AJ ONeal", IOUtil.readFullyAsStringSync(p.getReadableStream(), StandardCharsets.US_ASCII));
			} else if ("email".equals(name)) {
				Assert.assertEquals("coolaj86@gmail.com", IOUtil.readFullyAsStringSync(p.getReadableStream(), StandardCharsets.US_ASCII));
			} else if ("avatar".equals(name)) {
				// png
				Assert.assertEquals("image/png", p.getContentType());
			} else if ("attachments[]".equals(name)) {
				// text
				Assert.assertEquals("text/plain", p.getContentType());
			} else
				throw new AssertionError("Unexpected multipart form-data name " + name);
		}
	}
	
	@Test(timeout=120000)
	public void testGenerateMailWithMultipart() throws Exception {
		MultipartEntity mailText = new MultipartEntity("alternative");
		mailText.add(BinaryMimeEntity.fromString("Hello tester", StandardCharsets.UTF_8, "text/plain"));
		mailText.add(BinaryMimeEntity.fromString("<html><body>Hello tester</body></html>", StandardCharsets.UTF_8, "text/html"));
		mailText.addHeader("Subject", "This is a test");
		ByteBuffersIO out = new ByteBuffersIO(false, "Mail", Task.PRIORITY_NORMAL);
		AsyncWork<Long, IOException> copy = IOUtil.copy(mailText.createIOWithHeaders(), out, -1, false, null, 0);
		copy.blockThrow(0);
		out.seekSync(SeekType.FROM_BEGINNING, 0);
		String s = IOUtil.readFullyAsStringSync(out, StandardCharsets.UTF_8);
		System.out.println("_____________ Start of Mail ___________");
		System.out.println(s);
		System.out.println("_____________ End of Mail ___________");
		out.seekSync(SeekType.FROM_BEGINNING, 0);
		javax.mail.Session session = javax.mail.Session.getInstance(new Properties());
		MimeMessage mail = new MimeMessage(session, IOAsInputStream.get(out));
		Assert.assertEquals("This is a test", mail.getSubject());
		Object content = mail.getContent();
		Assert.assertTrue(content instanceof MimeMultipart);
		MimeMultipart m = (MimeMultipart)content;
		Assert.assertTrue("Final boundary found", m.isComplete());
		Assert.assertEquals(2, m.getCount());
		Assert.assertEquals("Hello tester", m.getBodyPart(0).getContent());
		Assert.assertEquals("<html><body>Hello tester</body></html>", m.getBodyPart(1).getContent());
	}
	
}
