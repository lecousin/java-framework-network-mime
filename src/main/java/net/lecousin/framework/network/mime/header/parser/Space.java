package net.lecousin.framework.network.mime.header.parser;

import net.lecousin.framework.text.IString;

/** Space token. */
public class Space implements Token {

	@Override
	public int textLength() {
		return 1;
	}
	
	@Override
	public void asText(IString s) {
		s.append(' ');
	}
	
}
