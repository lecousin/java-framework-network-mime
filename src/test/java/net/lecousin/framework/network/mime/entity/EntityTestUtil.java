package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Threading;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOFromInputStream;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.SimpleBufferedReadable;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

import org.junit.Assert;

public class EntityTestUtil {

	@SuppressWarnings("unchecked")
	public static <T extends MimeEntity> T generateAndParse(T source) throws Exception {
		// generate
		try (IO.Readable io = source.writeEntity().blockResult(0)) {
			// parse
			MimeEntity result = MimeEntity.parse(io, DefaultMimeEntityFactory.getInstance()).blockResult(0);
			// check type
			Assert.assertEquals(source.getClass(), result.getClass());
			return (T)result;
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends MimeEntity> T fromResource(String resourcePath, Class<T> expectedType) throws Exception {
		MimeEntity entity = MimeEntity.parse(new SimpleBufferedReadable(new IOFromInputStream(EntityTestUtil.class.getClassLoader().getResourceAsStream(resourcePath), resourcePath, Threading.getCPUTaskManager(), Task.Priority.NORMAL), 4096), DefaultMimeEntityFactory.getInstance()).blockResult(0);
		Assert.assertEquals(expectedType, entity.getClass());
		return (T)entity;
	}
	
	public static void testBodyRangeProducer(Supplier<MimeEntity> entityCreator) throws Exception {
		MimeEntity entity = entityCreator.get();
		Assert.assertTrue(entity.canProduceBodyRange());
		Pair<Long, AsyncProducer<ByteBuffer, IOException>> body = entity.createBodyProducer().blockResult(0);
		@SuppressWarnings("resource")
		ByteArrayIO io = new ByteArrayIO("mime body");
		body.getValue2().toConsumer(io.createConsumer(() -> {}, e -> {}), "body", Priority.NORMAL).blockThrow(0);
		byte[] buffer = io.getArray();
		int size = (int)io.getSizeSync();
		
		testRange(entityCreator.get(), 0, size, 0, size - 1, size, buffer);
		testRange(entityCreator.get(), 0, size - 1, 0, size - 1, size, buffer);
		testRange(entityCreator.get(), 0, 10, 0, 10, size, buffer);
		testRange(entityCreator.get(), 1, 10, 1, 10, size, buffer);
		testRange(entityCreator.get(), -1, 10, size - 10, size - 1, size, buffer);
		testRange(entityCreator.get(), 10, -1, 10, size - 1, size, buffer);
	}
	
	private static void testRange(MimeEntity entity, long start, long end, long expectedStart, long expectedEnd, int totalSize, byte[] totalBody) throws Exception {
		Triple<RangeLong, Long, BinaryEntity> rangeBody = entity.createBodyRange(new RangeLong(start, end));
		Assert.assertEquals(expectedStart, rangeBody.getValue1().min);
		Assert.assertEquals(expectedEnd, rangeBody.getValue1().max);
		Assert.assertEquals(totalSize, rangeBody.getValue2().intValue());
		IO.Readable content = rangeBody.getValue3().getContent();
		byte[] buffer = new byte[totalSize + 10];
		int read = IOUtil.readFully(content, ByteBuffer.wrap(buffer));
		Assert.assertEquals(expectedEnd - expectedStart + 1, read);
		for (int i = 0; i < read; ++i)
			Assert.assertEquals(totalBody[(int)(expectedStart + i)], buffer[i]);
	}
	
}
