package net.lecousin.framework.network.mime.header;

import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.parser.SpecialCharacter;
import net.lecousin.framework.network.mime.header.parser.Token;

/**
 * Comma separated list of header values.
 * @param <V> type of value
 */
public abstract class HeaderValues<V extends HeaderValueFormat> implements HeaderValueFormat {

	private List<V> values = new LinkedList<>();
	
	public List<V> getValues() {
		return values;
	}
	
	protected abstract V newValue();
	
	@Override
	public void parseTokens(List<Token> tokens) throws MimeException {
		values.clear();
		List<List<Token>> list = Token.splitBySpecialCharacter(tokens, ',');
		for (List<Token> subList : list) {
			V value = newValue();
			value.parseTokens(subList);
			values.add(value);
		}
	}
	
	@Override
	public List<Token> generateTokens() {
		List<Token> tokens = new LinkedList<>();
		for (V value : values) {
			if (!tokens.isEmpty())
				tokens.add(new SpecialCharacter(','));
			tokens.addAll(value.generateTokens());
		}
		return tokens;
	}
	
}
