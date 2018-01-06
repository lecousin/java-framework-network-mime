package net.lecousin.framework.network.mime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.Threading;
import net.lecousin.framework.concurrent.synch.AsyncWork;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOFromInputStream;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.network.mime.entity.FormUrlEncodedEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity;
import net.lecousin.framework.network.mime.entity.MultipartEntity.GenericPart;
import net.lecousin.framework.network.mime.entity.MultipartEntity.Part;
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
		ByteBuffersIO io = generate(source);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		FormUrlEncodedEntity target = new FormUrlEncodedEntity();
		SynchronizationPoint<IOException> parse = target.parse(io, StandardCharsets.UTF_8);
		parse.blockThrow(0);
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
	
	private static ByteBuffersIO generate(MimeEntity entity) throws Exception {
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
		for (Part p : entity.getParts()) {
			Assert.assertEquals(GenericPart.class, p.getClass());
			GenericPart gp = (GenericPart)p;
			Pair<String, Map<String,String>> dispo = gp.getHeader().parseParameterizedHeaderSingleValue("Content-Disposition");
			Assert.assertEquals("form-data", dispo.getValue1());
			String name = dispo.getValue2().get("name");
			if ("name".equals(name)) {
				Assert.assertEquals("AJ ONeal", IOUtil.readFullyAsStringSync(gp.getBody(), StandardCharsets.US_ASCII));
			} else if ("email".equals(name)) {
				Assert.assertEquals("coolaj86@gmail.com", IOUtil.readFullyAsStringSync(gp.getBody(), StandardCharsets.US_ASCII));
			} else if ("avatar".equals(name)) {
				// png
				Assert.assertEquals("image/png", gp.getHeader().getContentType());
			} else if ("attachments[]".equals(name)) {
				// text
				Assert.assertEquals("text/plain", gp.getHeader().getContentType());
			} else
				throw new AssertionError("Unexpected multipart form-data name " + name);
		}
	}
	
}
