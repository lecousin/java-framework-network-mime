package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

import net.lecousin.compression.gzip.GZipWritable;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.network.mime.entity.FormDataEntity.FormDataPartFactory;
import net.lecousin.framework.network.mime.entity.FormDataEntity.PartFile;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;

public class TestFormDataEntity extends LCCoreAbstractTest {
	
	@Test
	public void testFormData() throws Exception {
		FormDataEntity form = new FormDataEntity();
		
		Assert.assertFalse(form.canProduceBodyRange());
		Assert.assertNull(form.createBodyRange(new RangeLong(0L, 1L)));
		
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

		@SuppressWarnings("resource")
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
	public void testOneThousandFields() throws Exception {
		try (FormDataEntity form = new FormDataEntity()) {
			for (int i = 1; i <= 1000; ++i)
				form.addField("field" + i, "Value" + i, StandardCharsets.US_ASCII);
			try (FormDataEntity form2 = EntityTestUtil.generateAndParse(form)) {
				Assert.assertTrue(form2.getPartFactory() instanceof FormDataPartFactory);
				Assert.assertEquals(1000, form2.getFields().size());
				int index = 1;
				for (Pair<String, String> field : form2.getFields()) {
					Assert.assertEquals("field" + index, field.getValue1());
					Assert.assertEquals("Value" + index, field.getValue2());
					index++;
				}
			}
		}
	}
	
	@Test
	public void testLongField() throws Exception {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 100; ++i)
			s.append("This is a very very long name for a field...");
		String name = s.toString();
		s = new StringBuilder();
		for (int i = 0; i < 100; ++i)
			s.append("it has also a very very long value!");
		String value = s.toString();
		try (FormDataEntity form = new FormDataEntity()) {
			form.addField(name, value, StandardCharsets.US_ASCII);
			try (FormDataEntity form2 = EntityTestUtil.generateAndParse(form)) {
				Assert.assertEquals(1, form2.getFields().size());
				Assert.assertEquals(name, form2.getFields().get(0).getValue1());
				Assert.assertEquals(value, form2.getFields().get(0).getValue2());
			}
		}
	}

}
