package net.lecousin.framework.network.mime.entity;

import java.util.Iterator;

import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;

public class TestFormUrlEncodedEntity extends LCCoreAbstractTest {

	@Test
	public void testBasics() throws Exception {
		FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
		Assert.assertFalse(entity.canProduceBodyRange());
		Assert.assertNull(entity.createBodyRange(new RangeLong(0L, 1L)));
	}
	
	@Test
	public void testZeroParameter() throws Exception {
		FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
		checkSameParameters(entity, EntityTestUtil.generateAndParse(entity));
	}

	@Test
	public void testOneParameter() throws Exception {
		FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
		entity.add("key", "value");
		checkSameParameters(entity, EntityTestUtil.generateAndParse(entity));
	}
	
	@Test
	public void testTwoParameters() throws Exception {
		FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
		entity.add("key 1", "value 1");
		entity.add("key 2", "value 2");
		checkSameParameters(entity, EntityTestUtil.generateAndParse(entity));
	}
	
	@Test
	public void testOneThousandParameters() throws Exception {
		FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
		for (int i = 1; i<= 1000; ++i)
			entity.add("key " + i, "value " + i);
		checkSameParameters(entity, EntityTestUtil.generateAndParse(entity));
	}
	
	@Test
	public void testTwoParametersWithSpecialCharacters() throws Exception {
		FormUrlEncodedEntity entity = new FormUrlEncodedEntity();
		entity.add("key+%@=1", "value=%1");
		entity.add("key\"2", "value'2");
		checkSameParameters(entity, EntityTestUtil.generateAndParse(entity));
	}
	
	private static void checkSameParameters(FormUrlEncodedEntity source, FormUrlEncodedEntity target) throws Exception {
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
	public void testParseFile() throws Exception {
		FormUrlEncodedEntity entity = EntityTestUtil.fromResource("formurlencoded.raw", FormUrlEncodedEntity.class);
		Assert.assertTrue(entity.hasParameter("home"));
		Assert.assertTrue(entity.hasParameter("favorite flavor"));
		Assert.assertFalse(entity.hasParameter("hello"));
		Assert.assertEquals("Cosby", entity.getParameter("home"));
		Assert.assertEquals("flies", entity.getParameter("favorite flavor"));
		Assert.assertNull(entity.getParameter("hello"));
	}

}
