/*
 * Created on Apr 12, 2005
 *
 * @author Fabio Zadrozny
 */
package org.python.pydev.editor.correctionassist.docstrings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.Tuple;
import org.python.pydev.core.bundle.ImageCache;
import org.python.pydev.core.docutils.PySelection;
import org.python.pydev.editor.PyEdit;
import org.python.pydev.editor.actions.PyAction;
import org.python.pydev.editor.codecompletion.IPyCompletionProposal;
import org.python.pydev.editor.codecompletion.PyCompletionProposal;
import org.python.pydev.editor.correctionassist.heuristics.IAssistProps;
import org.python.pydev.ui.UIConstants;

public class AssistDocString implements IAssistProps {

    /**
     * @see org.python.pydev.editor.correctionassist.heuristics.IAssistProps#getProps(org.python.pydev.core.docutils.PySelection,
     *      org.python.pydev.core.bundle.ImageCache)
     */
    public List<ICompletionProposal> getProps(PySelection ps, ImageCache imageCache, File f, IPythonNature nature, PyEdit edit, int offset)
            throws BadLocationException {
        ArrayList<ICompletionProposal> l = new ArrayList<ICompletionProposal>();
        Tuple<List<String>, Integer> tuple = ps.getInsideParentesisToks(false);

        if (tuple == null) {
            return l;
        }
        List<String> params = tuple.o1;

        String initial = PySelection.getIndentationFromLine(ps.getCursorLineContents());
        String delimiter = PyAction.getDelimiter(ps.getDoc());
        String indentation = PyAction.getStaticIndentationString(edit);
        String inAndIndent = delimiter + initial + indentation;

        StringBuffer buf = new StringBuffer();
        String docStringMarker = DocstringsPrefPage.getDocstringMarker();
        buf.append(inAndIndent + docStringMarker);
        buf.append(inAndIndent);

        int newOffset = buf.length();
        if (FunctionPattern.matcher(ps.getCursorLineContents()).matches()) {
            for (String paramName : params) {
                buf.append(inAndIndent + "@param " + paramName + ":");
                if (DocstringsPrefPage.typeTagShouldBeGenerated(paramName)) {
                    buf.append(inAndIndent + "@type " + paramName + ":");
                }
            }
        } else {
            // It's a class declaration - do nothing.
        }
        buf.append(inAndIndent + docStringMarker);
        buf.append(inAndIndent);

        int lineOfOffset = ps.getLineOfOffset(tuple.o2);
        String comp = buf.toString();
        int offsetPosToAdd = ps.getEndLineOffset(lineOfOffset);

        l.add(new PyCompletionProposal(comp, offsetPosToAdd, 0, newOffset, imageCache.get(UIConstants.ASSIST_DOCSTRING), "Make docstring",
                null, null, IPyCompletionProposal.PRIORITY_DEFAULT));
        return l;
    }

    /**
     * @see org.python.pydev.editor.correctionassist.heuristics.IAssistProps#isValid(org.python.pydev.core.docutils.PySelection,
     *      java.lang.String)
     */
    public boolean isValid(PySelection ps, String sel, PyEdit edit, int offset) {
        return FunctionPattern.matcher(sel).matches() || ClassPattern.matcher(sel).matches();
    }

    private static final String identifierPattern = "[a-zA-Z_]\\w*";

    private static final String argumentsPattern = "(" + "\\s*" + identifierPattern + "\\s*" + "(?:\\s*,\\s*" + identifierPattern
            + "\\s*)*" + ")?\\s*"; // The last whitespace is for empty lists
                                    // with a space between the parentheses.

    public static final Pattern FunctionPattern = Pattern.compile("\\s*def\\s+" + identifierPattern + "\\s*\\(" + argumentsPattern
            + "\\)\\s*:\\s*");

    public static final Pattern ClassPattern = Pattern.compile("class\\s+" + identifierPattern
            + "\\s*(?:\\(\\s*[a-zA-Z_.]+\\s*\\))?\\s*:\\s*");
}
