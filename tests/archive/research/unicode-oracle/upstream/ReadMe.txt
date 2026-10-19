ReadMe.txt for the Java Reference Implementation of the Unicode Bidirectional Algorithm

The Unicode Standard, Version 8.0.0

July 7, 2017

Authors: Doug Felt, Asmus Freytag, Roozbeh Pournader, Deepak Jois


1. Overview

The Java reference implementation of the Unicode Bidirectional Algorithm (UBA) is furnished
as Java source code available at http://www.unicode.org/Public/PROGRAMS/BidiReferenceJava/.
The implementation follows the specification of the UBA in Version 8.0.0 of the Unicode Standard.
The "archive-2009-09-02" and "archive-2013-09-16" subdirectories 
contain Java sources of implementations of former
versions of the UBA and are for archival purposes only.

The source code is distributed, under the general Unicode Character Database terms of use
(see http://www.unicode.org/terms_of_use.html), both for compilation on any required platform
and as a general model for other implementations, to assist in correct interpretation of the
rather complicated steps of the specification of the algorithm in Unicode Standard Annex #9,
Unicode Bidirectional Algorithm (see http://www.unicode.org/reports/tr9/).

The implementation provides both a command-line interactive test tool and an API for programmatic
use. The interactive tool can be used to inspect the results of bidirectional reordering after
running the UBA on strings entered at the tool's prompt. The API can be used to programmatically
execute the UBA on a single input string, which may be useful in building test frameworks that
systematically compare different implementations or for applications that simply want to check
or illustrate behavior in particular cases.


2. Main classes

The Java sources can be built, as usual, by invoking the Java compiler from the command prompt
or by creating and building a project in an integrated development environment. The main class
for the interactive test tool is BidiReferenceTest and the API functions are in the BidiReference
class. The part of the UBA that processes bracket pairs is implemented in the BidiPBAtReference
class. Two additional classes, BidiTestBracketMap and BidiReferenceTestCharmap, provide mapping
conventions for interactive testing. All classes are defined in the org.unicode.bidi namespace.


3. Using the interactive test tool

The interactive test tool accepts input in several mapping conventions. The mappings and the
commands to switch between them are listed in the help message printed out when the tool is run.
Different mappings give different interpretations to the same input characters. For example,
the characters '[', ']', '{', '}', '<', and '>' are treated as paired brackets in the mapping
selected with the "-pba" command, but as the bidirectional format characters LRE, RLE, LRO, RLO,
LRI, and RLI in the other mappings supported. Note that the input characters recognized by the
tool may not necessarily denote the bidirectional types of those characters in the current version
of the Unicode Standard. For example, the characters '+' and '-' are treated as European_Terminator
characters, although their Bidi_Class property value is European_Separator in recent versions of
the Unicode Standard. Use the command "-display" to view the current input mapping. Only a single
Paragraph_Separator character is allowed, as the last character of the input string.

The tool runs the UBA through rule L2 on an input string and prints out the reordered string
(without applying mirroring, which would occur in rule L4). When the "-pba" mapping is selected,
the tool additionally starts displaying the text positions of the bracket pairs identified in
the input string and the resolved directional types. If the input string contains directional
isolate characters, only the bracket pairs within the last isolating run sequence are displayed.
To view all bracket pairs when isolate characters are present, use input strings that contain a
single isolating run sequence.


4. Using the API

To run the UBA on an input string, call the org.unicode.bidi.BidiReference.analyzeInput() static
method with the appropriate parameters built from the input string. See the Javadoc comments for
a description of the expected parameters. Only one Paragraph_Separator element is allowed in the
input array of bidirectional character types, as the last array element. The function returns
an object of type BidiReference, on which the following instance methods can be called to obtain
the respective UBA results: getBaseLevel(), getLevels(), and getReordering(). The BidiReference
class exposes static fields with names matching the symbolic Bidi_Class property values. The static
method isRemovedByX9() is provided for convenient determination if a given bidirectional type is
one of the types removed in rule X9 of the UBA, for instance for stripping the affected entries
from the result arrays.
