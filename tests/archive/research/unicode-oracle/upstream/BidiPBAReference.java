package org.unicode.bidi;

/*
 * Last Revised: 2016-09-21
 * 
 * Credits:
 * Originally written by Asmus Freytag
 *
 * Updated for Unicode 8.0 by Deepak Jois, with feedback from Ken Whistler
 *
 * (C) Copyright ASMUS, Inc. 2013, All Rights Reserved
 * (C) Copyright Deepak Jois 2016, All Rights Reserved
 *
 * Distributed under the Terms of Use in http://www.unicode.org/copyright.html.
 */

/*
 * Revision info (2016-09-21):
 *  - Added MAX_PAIRING_DEPTH to support max depth for nested brackets in Unicode 8.0
 *  - Changes to support updated definitions BD14 and BD15 in Unicode 8.0
 *  - Changes to support clarifications to rule N0 in Unicode 8.0
 */


/**
 * Reference implementation of the BPA algorithm of the Unicode 6.3 Bidi algorithm.
 *
 * <p>
 * This implementation is not optimized for performance.  It is intended
 * as a reference implementation that closely follows the specification
 * of the paired bracket part of the Bidirectional Algorithm in 
 * The Unicode Standard version 6.3 (and revised in Unicode Standard version 8.0)
 * <p>
 * The implementation covers definitions BD14-BD16 and rule N0.
 * <p>
 * Like the BidiReference class which uses the BidiBPAReference class, the implementation is
 * designed to decouple the mapping of Unicode properties to characters from the handling
 * of the Bidi Paired-bracket Algorithm. Such mappings are to be performed by the caller.
 * <p>
 * One of the properties, the Bidi_Paired_Bracket requires some pre-processing to translate
 * it into the format used here. Instead of being a code-point mapping from a bracket character
 * to the other partner of the bracket pair, this implementation accepts any unique identifier
 * common to BOTH parts of the pair, and 0 or some unique value for all non-bracket characters.
 * The actual values of these unique identifiers are not defined.
 * <p>
 * The BPA algorithm requires that bracket characters that are canonical equivalents of each
 * other must be able to be substituted for each other. Callers can accomplish this by re-using
 * the same unique identifier for such equivalent characters.
 * <p>
 * The resultant values become the pairValues array used in calling the resolvePairedBrackets member.
 * <p>
 * In implementing BD16, this implementation departs slightly from the "logical" algorithm defined
 * in UAX#9. In particular, the stack referenced there supports operations that go beyond a "basic"
 * stack. An equivalent implementation based on a linked list is used here.
 * 
 * @author Asmus Freytag
 * @author Deepak Jois
 */

import java.util.*;

public class BidiPBAReference {

	/*
	 * BD14. An opening paired bracket is a character whose
	 * Bidi_Paired_Bracket_Type property value is Open.
	 * 
	 * BD15. A closing paired bracket is a character whose
	 * Bidi_Paired_Bracket_Type property value is Close.
	 */

	// Bidi_Paired_Bracket_Type
	public final static byte n = 0;
	public final static byte o = 1;
	public final static byte c = 2;

	byte sos; // direction corresponding to start of sequence
	
	public BidiPBAReference() {

	}

	// Holds a pair of index values for opening and closing bracket location of
	// a bracket pair
	// Contains additional methods to allow pairs to be sorted by the location
	// of the opening bracket
	public static final class BracketPair implements
			java.lang.Comparable<BracketPair> {
		private int ichOpener;
		private int ichCloser;

		public BracketPair(int ichOpener, int ichCloser) {
			super();
			this.ichOpener = ichOpener;
			this.ichCloser = ichCloser;
		}

		public boolean equals(Object other) {
			if (other instanceof BracketPair) {
				BracketPair otherPair = (BracketPair) other;
				return this.ichOpener == otherPair.ichOpener
						&& this.ichCloser == otherPair.ichCloser;
			}

			return false;
		}

		public int compareTo(BracketPair otherPair) {
			if (this.ichOpener == otherPair.ichOpener)
				return 0;
			if (this.ichOpener < otherPair.ichOpener)
				return -1;
			else
				return 1;
		}

		public String toString() {
			return "(" + ichOpener + ", " + ichCloser + ")";
		}

		public int getOpener() {
			return ichOpener;
		}

		public int getCloser() {
			return ichCloser;
		}
	}

	// The following is a restatement of BD 16 using non-algorithmic language. 
	//
	// A bracket pair is a pair of characters consisting of an opening
	// paired bracket and a closing paired bracket such that the
	// Bidi_Paired_Bracket property value of the former equals the latter,
	// subject to the following constraints.
	// - both characters of a pair occur in the same isolating run sequence
	// - the closing character of a pair follows the opening character
	// - any bracket character can belong at most to one pair, the earliest possible one
	// - any bracket character not part of a pair is treated like an ordinary character
	// - pairs may nest properly, but their spans may not overlap otherwise
	
	// Bracket characters with canonical decompositions are supposed to be treated
	// as if they had been normalized, to allow normalized and non-normalized text
	// to give the same result. In this implementation that step is pushed out to
	// the caller - see definition of the pairValues array.

	LinkedList<Integer> openers; // list of positions for opening brackets
	
	// bracket pair positions sorted by location of opening bracket
	private SortedSet<BidiPBAReference.BracketPair> pairPositions;
	
	public String getPairPositionsString()
	{
		SortedSet<BidiPBAReference.BracketPair> tempPositions = new TreeSet<BidiPBAReference.BracketPair>();
		for (BidiPBAReference.BracketPair pair : pairPositions) {
			tempPositions.add(new BidiPBAReference.BracketPair(indexes[pair.getOpener()], indexes[pair.getCloser()]));
		}
		return tempPositions.toString();
	}

	private byte[] initialCodes; // direction bidi codes initially assigned to the original string
	public byte[] codesIsolatedRun; // directional bidi codes for an isolated run
	private int[] indexes; // array of index values into the original string

	/**
	 * check whether characters at putative positions could form a bracket pair
	 * based on the paired bracket character properties
	 * 
	 * @param pairValues
	 *            - unique ID for the pair (or set) of canonically matched
	 *            brackets
	 * @param ichOpener
	 *            - position of the opening bracket
	 * @param ichCloser
	 *            - position of the closing bracket
	 * @return true if match
	 */
	private Boolean matchOpener(int[] pairValues, int ichOpener, int ichCloser) {
		return pairValues[indexes[ichOpener]] == pairValues[indexes[ichCloser]];
	}

	/**
	 * removes any list elements from First to index
	 * 
	 * @param list
	 * @param index
	 */
	private void removeHead(LinkedList<Integer> list, int index) {
		ListIterator<Integer> iter = list.listIterator(index);

		while (iter.hasPrevious()) {
			iter.previous();
			iter.remove();
		}
	}

	public static final int MAX_PAIRING_DEPTH = 63;

	/**
	 * locate all Paired Bracket characters and determine whether they form
	 * pairs according to BD16. This implementation uses a linked list instead
	 * of a stack, because, while elements are added at the front (like a push)
	 * there are not generally removed in atomic 'pop' operations, reducing the
	 * benefit of the stack archetype.
	 * 
	 * @param pairTypes
	 *            - array of paired Bracket types
	 * @param pairValues
	 *            - array of characters codes such that for all bracket
	 *            characters it contains the same unique value if their
	 *            Bidi_Paired_Bracket properties map between them. For 
	 *            brackets hat have canonical decompositions (singleton 
	 *            mappings) it contains the same value as for the canonically 
	 *            decomposed character. For characters that have paired 
	 *            bracket type of "n" the value is ignored.
	 */
	private void locateBrackets(byte[] pairTypes, int[] pairValues) {
		openers = new LinkedList<Integer>();
		pairPositions = new TreeSet<BracketPair>();

		// traverse the run
		// do that explicitly (not in a for-each) so we can record position
		for (int ich = 0; ich < indexes.length; ich++) {
			// look at the bracket type for each character
			if ((pairTypes[indexes[ich]] == n) || (codesIsolatedRun[ich] != BidiReference.ON)) {
				continue; // continue scanning
			}

			switch (pairTypes[indexes[ich]]) {
			// opening bracket found, note location
			case o:
				// check if maximum pairing depth reached
				if (openers.size() == MAX_PAIRING_DEPTH) {
					openers.clear();
					return;
				}

				// remember opener location, most recent first
				openers.addFirst(ich);
				break;

			// closing bracket found
			case c:
				// see if there is a match
				if (openers.isEmpty())
					continue; // no opening bracket defined

				ListIterator<Integer> iter = openers.listIterator();

				while (iter.hasNext()) {
					Integer opener = iter.next();
					if (matchOpener(pairValues, opener, ich)) {
						// if the opener matches, add nested pair to the ordered list
						pairPositions
								.add(new BracketPair(opener, (Integer) ich));
						// remove up to and including matched opener
						removeHead(openers, iter.nextIndex());
						break;
					}
				}
				// if we get here, the closing bracket matched no openers
				// and gets ignored
				continue;
			}
		}
	}

	/*
	 * Bracket pairs within an isolating run sequence are processed as units so
	 * that both the opening and the closing paired bracket in a pair resolve to
	 * the same direction.
	 * 
	 * N0. Process bracket pairs in an isolating run sequence sequentially in
	 * the logical order of the text positions of the opening paired brackets
	 * using the logic given below. Within this scope, bidirectional types EN
	 * and AN are treated as R.
	 * 
	 * Identify the bracket pairs in the current isolating run sequence
	 * according to BD16. For each bracket-pair element in the list of pairs of
	 * text positions:
	 * 
	 * a Inspect the bidirectional types of the characters enclosed within the
	 * bracket pair.
	 * 
	 * b If any strong type (either L or R) matching the embedding direction is
	 * found, set the type for both brackets in the pair to match the embedding
	 * direction.
	 * 
	 * o [ e ] o -> o e e e o
	 * 
	 * o [ o e ] -> o e o e e
	 * 
	 * o [ NI e ] -> o e NI e e
	 * 
	 * c Otherwise, if a strong type (opposite the embedding direction) is
	 * found, test for adjacent strong types as follows: 1 First, check
	 * backwards before the opening paired bracket until the first strong type
	 * (L, R, or sos) is found. If that first preceding strong type is opposite
	 * the embedding direction, then set the type for both brackets in the pair
	 * to that type. 2 Otherwise, set the type for both brackets in the pair to
	 * the embedding direction.
	 * 
	 * o [ o ] e -> o o o o e
	 * 
	 * o [ o NI ] o -> o o o NI o o
	 * 
	 * e [ o ] o -> e e o e o
	 * 
	 * e [ o ] e -> e e o e e
	 * 
	 * e ( o [ o ] NI ) e -> e e o o o o NI e e
	 * 
	 * d Otherwise, do not set the type for the current bracket pair. Note that
	 * if the enclosed text contains no strong types the paired brackets will
	 * both resolve to the same level when resolved individually using rules N1
	 * and N2.
	 * 
	 * e ( NI ) o -> e ( NI ) o
	 */

	/**
	 * map character's directional code to strong type as required by rule N0
	 * 
	 * @param ich
	 *            - index into array of directional codes
	 * @return R or L for strong directional codes, ON for anything else
	 */
	private byte getStrongTypeN0(int ich) {

		switch (codesIsolatedRun[ich]) {
		default:
			return BidiReference.ON;
			// in the scope of N0, number types are treated as R
		case BidiReference.EN:
		case BidiReference.AN:
		case BidiReference.AL:
		case BidiReference.R:
			return BidiReference.R;
		case BidiReference.L:
			return BidiReference.L;
		}
	}

	/**
	 * determine which strong types are contained inside a Bracket Pair
	 * 
	 * @param pairedLocation
	 *            - a bracket pair
	 * @param dirEmbed
	 *            - the embedding direction
	 * @return ON if no strong type found, otherwise return the embedding
	 *         direction, unless the only strong type found is opposite the
	 *         embedding direction, in which case that is returned
	 */
	byte classifyPairContent(BracketPair pairedLocation, byte dirEmbed) {
		byte dirOpposite = BidiReference.ON;
		for (int ich = pairedLocation.getOpener() + 1; ich < pairedLocation
				.getCloser(); ich++) {
			byte dir = getStrongTypeN0(ich);
			if (dir == BidiReference.ON)
				continue;
			if (dir == dirEmbed)
				return dir; // type matching embedding direction found
			dirOpposite = dir;
		}
		// return ON if no strong type found, or class opposite to dirEmbed
		return dirOpposite;
	}

	/**
	 * determine which strong types are present before a Bracket Pair
	 * 
	 * @param pairedLocation
	 *            - a bracket pair
	 * @return R or L if strong type found, otherwise ON
	 */
	byte classBeforePair(BracketPair pairedLocation) {
		for (int ich = pairedLocation.getOpener() - 1; ich >= 0; --ich) {
			byte dir = getStrongTypeN0(ich);
			if (dir != BidiReference.ON)
				return dir;
		}
		// no strong types found, return sos
		return sos;
	}

	/**
	 * Implement rule N0 for a single bracket pair
	 * 
	 * @param pairedLocation
	 *            - a bracket pair
	 * @param dirEmbed
	 *            - the embedding direction
	 */
	void assignBracketType(BracketPair pairedLocation, byte dirEmbed) {
		// rule "N0, a", inspect contents of pair
		byte dirPair = classifyPairContent(pairedLocation, dirEmbed);
		
	    // dirPair is now L, R, or N (no strong type found)

		// the following logical tests are performed out of order compared to
		// the statement of the rules but yield the same results
		if (dirPair == BidiReference.ON)
			return; // case "d" - nothing to do

		if (dirPair != dirEmbed) {
			// case "c": strong type found, opposite - check before (c.1)
			dirPair = classBeforePair(pairedLocation);
			if (dirPair == dirEmbed || dirPair == BidiReference.ON) {
				// no strong opposite type found before - use embedding (c.2)
				dirPair = dirEmbed;
			}
		}
		// else: case "b", strong type found matching embedding,
		// no explicit action needed, as dirPair is already set to embedding
		// direction

		// set the bracket types to the type found
		setBracketsToType(pairedLocation, dirPair);
	}

	private void setBracketsToType(BracketPair pairedLocation, byte dirPair) {
		codesIsolatedRun[pairedLocation.getOpener()] = dirPair;
		codesIsolatedRun[pairedLocation.getCloser()] = dirPair;

		for(int i = pairedLocation.getOpener() + 1; i < pairedLocation.getCloser(); i++) {
			int index = indexes[i];
			if (initialCodes[index] == BidiReference.NSM) {
				codesIsolatedRun[i] = dirPair;
			} else {
				break;
			}
		}

		for(int i = pairedLocation.getCloser() + 1; i < indexes.length; i++) {
			int index = indexes[i];
			if (initialCodes[index] == BidiReference.NSM) {
				codesIsolatedRun[i] = dirPair;
			} else {
				break;
			}
		}
	}

	// this implements rule N0 for a list of pairs
	public void resolveBrackets(byte dirEmbed) {
		for (BracketPair pair : pairPositions) {
			assignBracketType(pair, dirEmbed);
		}
	}

	/**
	 * runAlgorithm - runs the paired bracket part of the UBA algorithm
	 * 
	 * @param indexes
	 *            - indexes into the original string
	 * @param initialCodes
	 * 			  - bidi classes (directional codes) initially assigned to each
	 * 			  character in the original string (prior to any modifications by
	 * 			  subsequent steps.
	 * @param codes
	 *            - bidi classes (directional codes) for each character in the
	 *            original string
	 * @param pairTypes
	 *            - array of paired bracket types for each character in the
	 *            original string 
	 * @param pairValues
	 *            - array of unique integers identifying which pair of brackets 
	 *            (or canonically equivalent set) a bracket character
	 *            belongs to. For example in the string "[Test(s)>" the
	 *            characters "(" and ")" would share one value and "[" and ">"
	 *            share another (assuming that "]" and ">" are canonically equivalent).
	 *            Characters that have pairType = n might always get pairValue = 0.
	 *            
	 *            The actual values are no important as long as they are unique,
	 *            so one way to assign them is to use the code position value for
	 *            the closing element of a paired set for both opening and closing
	 *            character - paying attention to first applying canonical decomposition.
	 * @param sos
	 *            - direction for sos
	 * @param level
	 *            - the embedding level
	 */
	public void resolvePairedBrackets(int[] indexes, byte[] initialCodes, byte[] codes, byte[] pairTypes,
			int[] pairValues, byte sos, byte level) {
		final byte dirEmbed = 1 == (level & 1) ? BidiReference.R
				: BidiReference.L;
		this.sos = sos;
		this.indexes = indexes;
		codesIsolatedRun = codes;
		this.initialCodes = initialCodes;
		locateBrackets(pairTypes, pairValues);
		resolveBrackets(dirEmbed);
	}
	
	/**
	 * Entry point for testing the BPA algorithm in isolation. Does not use an indexes
	 * array for indirection. Actual work is carried out by resolvePairedBrackets.
	 * 
	 * @param codes
	 *            - bidi classes (directional codes) for each character
	 * @param pairTypes
	 *            - array of paired bracket type values for each character
	 * @param pairValues
	 *            - array of unique integers identifying which bracket pair
	 *            see resolvePairedBrackets for details.
	 * @param sos
	 *            - direction for sos
	 * @param level
	 *            - the embedding level
	 */
	public void runAlgorithm(byte[] codes, byte[] pairTypes,
			int[] pairValues, byte sos, byte level) {
		
		// dummy up an indexes array that represents an identity mapping
		this.indexes = new int[codes.length];
		for (int ich = 0; ich < indexes.length; ich++)
			indexes[ich] = ich;
		resolvePairedBrackets(indexes, codes, codes, pairTypes, pairValues, sos, level);
	}
}
