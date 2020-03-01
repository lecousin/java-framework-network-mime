package net.lecousin.framework.network.mime.entity;

import java.util.HashMap;
import java.util.Map;

import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;

/** Factory to create a MimeEntity based on its content type. */
public final class DefaultMimeEntityFactory implements MimeEntityFactory {

	private static final DefaultMimeEntityFactory instance = new DefaultMimeEntityFactory();
	
	public static DefaultMimeEntityFactory getInstance() {
		return instance;
	}
	
	private Map<String, Map<String, Class<? extends MimeEntity>>> registry = new HashMap<>();
	
	private DefaultMimeEntityFactory() {
		register(null, null, BinaryEntity.class);
		register("text", null, TextEntity.class);
		register(MultipartEntity.MAIN_CONTENT_TYPE, FormDataEntity.MULTIPART_SUB_TYPE, FormDataEntity.class);
		register(MultipartEntity.MAIN_CONTENT_TYPE, null, MultipartEntity.class);
		register("application", "x-www-form-urlencoded", FormUrlEncodedEntity.class);
	}
	
	/** Register a MimeEntity class for a given content type. */
	public void register(String mainType, String subType, Class<? extends MimeEntity> clazz) {
		synchronized (registry) {
			Map<String, Class<? extends MimeEntity>> subMap = registry.get(mainType);
			if (subMap == null) {
				subMap = new HashMap<>();
				registry.put(mainType, subMap);
			}
			subMap.put(subType, clazz);
		}
	}
	
	/** Create a MimeEntity based on its content type. */
	@Override
	public MimeEntity create(MimeEntity parent, MimeHeaders headers) {
		ParameterizedHeaderValue ct;
		try { ct = headers.getContentType(); }
		catch (MimeException e) { ct = null; }
		String main = null;
		String sub = null;
		if (ct != null) {
			String s = ct.getMainValue();
			int i = s.indexOf('/');
			if (i > 0) {
				main = s.substring(0, i);
				sub = s.substring(i + 1);
			}
		}
		Map<String, Class<? extends MimeEntity>> subMap = registry.get(main);
		if (subMap == null)
			subMap = registry.get(null);
		if (subMap == null)
			return new BinaryEntity(parent, headers);
		Class<? extends MimeEntity> type = subMap.get(sub);
		if (type == null)
			type = subMap.get(null);
		if (type == null)
			return new BinaryEntity(parent, headers);
		try {
			return type.getConstructor(MimeEntity.class, MimeHeaders.class).newInstance(parent, headers);
		} catch (Exception e) {
			return null;
		}
	}
	
}
