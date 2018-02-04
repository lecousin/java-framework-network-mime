package net.lecousin.framework.network.mime.header;

import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.network.mime.header.parser.SpecialCharacter;
import net.lecousin.framework.network.mime.header.parser.Token;

/**
 * Comma separated list of parameterized header values.
 */
public class ParameterizedHeaderValues implements HeaderValueFormat {

	private List<ParameterizedHeaderValue> values = new LinkedList<>();
	
	public List<ParameterizedHeaderValue> getValues() {
		return values;
	}
	
	/** Return the value having the given main value. */
	public ParameterizedHeaderValue getMainValue(String value) {
		for (ParameterizedHeaderValue v : values)
			if (value.equals(v.getMainValue()))
				return v;
		return null;
	}
	
	/** Return true if a value has the given main value. */
	public boolean hasMainValue(String value) {
		return getMainValue(value) != null;
	}
	
	@Override
	public void parseTokens(List<Token> tokens) throws Exception {
		values.clear();
		List<List<Token>> list = Token.splitBySpecialCharacter(tokens, ',');
		for (List<Token> subList : list) {
			ParameterizedHeaderValue value = new ParameterizedHeaderValue();
			value.parseTokens(subList);
			values.add(value);
		}
	}
	
	@Override
	public List<Token> generateTokens() {
		List<Token> tokens = new LinkedList<>();
		for (ParameterizedHeaderValue value : values) {
			if (!tokens.isEmpty())
				tokens.add(new SpecialCharacter(','));
			tokens.addAll(value.generateTokens());
		}
		return tokens;
	}
	
}
