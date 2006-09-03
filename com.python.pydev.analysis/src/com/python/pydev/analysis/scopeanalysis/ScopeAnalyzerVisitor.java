/*
 * Created on May 16, 2006
 */
package com.python.pydev.analysis.scopeanalysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.python.pydev.core.FullRepIterable;
import org.python.pydev.core.IModule;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.IToken;
import org.python.pydev.core.Tuple;
import org.python.pydev.core.Tuple3;
import org.python.pydev.core.docutils.PySelection;
import org.python.pydev.core.log.Log;
import org.python.pydev.core.structure.FastStack;
import org.python.pydev.editor.codecompletion.revisited.modules.SourceToken;
import org.python.pydev.editor.codecompletion.revisited.visitors.AbstractVisitor;
import org.python.pydev.parser.jython.SimpleNode;
import org.python.pydev.parser.jython.ast.Assign;
import org.python.pydev.parser.jython.ast.Import;
import org.python.pydev.parser.jython.ast.ImportFrom;
import org.python.pydev.parser.jython.ast.Name;
import org.python.pydev.parser.jython.ast.NameTok;
import org.python.pydev.parser.jython.ast.aliasType;
import org.python.pydev.parser.visitors.NodeUtils;
import org.python.pydev.parser.visitors.scope.ASTEntry;

import com.python.pydev.analysis.messages.AbstractMessage;
import com.python.pydev.analysis.visitors.Found;
import com.python.pydev.analysis.visitors.GenAndTok;
import com.python.pydev.analysis.visitors.ScopeItems;

/**
 * This class is used to discover the occurrences of some token having its scope as something important.
 */
public class ScopeAnalyzerVisitor extends AbstractScopeAnalyzerVisitor{

    private String completeNameToFind="";
    private String nameToFind="";
	private PySelection ps;
	private List<Tuple3<Found, Integer, ASTEntry>> foundOccurrences = new ArrayList<Tuple3<Found, Integer, ASTEntry>>();
	private FastStack<ASTEntry> parents; //initialized on demand
	
	/**
	 * Keeps the variables that are really undefined (we keep them here if there's still a chance that
	 * what we're looking for is an undefined variable and all in the same scope should also be marked
	 * as that same undefined).
	 */
	private List<Found> undefinedFound = new ArrayList<Found>();
	/**
	 * This one is not null only if it is the name we're looking for in the exact position (even if it does
	 * not have a definition).
	 */
	private Found hitAsUndefined = null;
	
	private boolean finished = false;

	public ScopeAnalyzerVisitor(IPythonNature nature, String moduleName, IModule current,  
            IDocument document, IProgressMonitor monitor, PySelection ps) {
        super(nature, moduleName, current, document, monitor);
        
        try {
			Tuple<String, Integer> currToken = ps.getCurrToken();
			nameToFind = currToken.o1;
			
			String[] tokenAndQual = ps.getActivationTokenAndQual(true);
			completeNameToFind = tokenAndQual[0]+tokenAndQual[1];
			
		} catch (BadLocationException e) {
			Log.log(e);
		}
		this.ps = ps;
    }



    @Override
    protected void onLastScope(ScopeItems m) {
		//not found
        for(Found found: probablyNotDefined){
        	ASTEntry parent = peekParent();
        	if(checkFound(found, parent) == null){
        		//ok, it was actually not found, so, after marking it as an occurrence, we have to check all 
        		//the others that have the same representation in its scope.
        		String rep = found.getSingle().generator.getRepresentation();
        		if(FullRepIterable.containsPart(rep, nameToFind)){
        			undefinedFound.add(found);
        		}
        	}else{
        		hitAsUndefined = found;
        	}
        }
    }
    
    @Override
    public void onAddUnusedMessage(Found found) {
    }

    @Override
    public void onAddReimportMessage(Found newFound) {
    }

    @Override
    public void onAddUnresolvedImport(IToken token) {
    }

    @Override
    public void onAddDuplicatedSignature(SourceToken token, String name) {
    }

    @Override
    public void onAddNoSelf(SourceToken token, Object[] objects) {
    }
    
    @Override
    protected void onAfterAddToNamesToIgnore(ScopeItems currScopeItems, Tuple<IToken, Found> tup) {
    	if(tup.o1 instanceof SourceToken){
			checkFound(tup.o2, peekParent());
    	}
    }
    
    @Override
    protected boolean doCheckIsInNamesToIgnore(String rep, IToken token) {
    	org.python.pydev.core.Tuple<IToken, Found> found = scope.isInNamesToIgnore(rep);
    	if(found != null){
    		found.o2.getSingle().references.add(token);
    	}
    	return found != null;
    }
    

    @Override
    protected void onFoundUnresolvedImportPart(IToken token, String rep, Found foundAs) {
        onAddUndefinedMessage(token, foundAs);
    }
    
    @Override
    protected void onAddUndefinedVarInImportMessage(IToken token, Found foundAs) {
        onAddUndefinedMessage(token, foundAs);
    }

    
    @Override
    protected void onAddUndefinedMessage(IToken token, Found found) {
    	ASTEntry parent = peekParent();
    	if(checkFound(found, parent) == null){
    		//ok, it was actually not found, so, after marking it as an occurrence, we have to check all 
    		//the others that have the same representation in its scope.
    		if(token.getRepresentation().equals(nameToFind)){
    			undefinedFound.add(found);
    		}
    	}else{
    		hitAsUndefined = found;
    	}
    }


    /**
     * If the 'parents' stack is higher than 0, peek it (may return null)
     */
	private ASTEntry peekParent() {
		ASTEntry parent = null;
    	if(parents.size() > 0){
    		parent = parents.peek();
    	}
		return parent;
	}

	/**
	 * If the 'parents' stack is higher than 0, pop it (may return null)
	 */
	private ASTEntry popParent(SimpleNode node) {
		ASTEntry parent = null;
    	if(node != null){
    		parent = parents.pop();
    	}
		return parent;
	}
    
	/**
	 * When we start the scope, we have to put an entry in the parents.
	 */
	@Override
	protected void onAfterStartScope(int newScopeType, SimpleNode node) {
		if(parents == null){
			parents = new FastStack<ASTEntry>();
		}
		if(node == null){
			return;
		}
		if(parents.size() == 0){
			parents.push(new ASTEntry(null, node));
		}else{
			parents.add(new ASTEntry(parents.peek(), node));
		}
	}

	@Override
	protected void onBeforeEndScope(SimpleNode node) {
	}

	@Override
	protected void onAfterVisitAssign(Assign node) {
	}
    
	/**
	 * If it is still not finished we'll have to finish it (end the last scope).
	 */
	private void checkFinished() {
		if(!finished){
			finished = true;
			endScope(null); //finish the last scope
		}
	}

    @Override
    protected void onAfterEndScope(SimpleNode node, ScopeItems m) {
        if(hitAsUndefined == null){
            for (String rep : new FullRepIterable(this.completeNameToFind, true)){
                Found found = m.get(rep);
                if(found != null){
                    if(checkFound(node, found) != null){
                        return;
                    }
                }
                
            }
            
        }else{ //(hitAsUndefined != null)
            
            String foundRep = hitAsUndefined.getSingle().generator.getRepresentation();
            
            if(foundRep.indexOf('.') == -1 || FullRepIterable.containsPart(foundRep,nameToFind)){
                //now, there's a catch here, if we found it as an attribute,
                //we cannot get the locals
                for(Found f :this.undefinedFound){
                	if(f.getSingle().generator.getRepresentation().startsWith(foundRep)){
//                    if(f.getSingle().scopeFound == hitAsUndefined.getSingle().scopeFound){
                        if (foundOccurrences.size() == 1){
	                        Tuple3<Found, Integer, ASTEntry> hit = foundOccurrences.get(0);
	                        foundOccurrences.add(new Tuple3<Found, Integer, ASTEntry>(f, hit.o2, hit.o3));
                        }
//                    }
                	}
                }
            }
    	}
    	
    }

    private Found checkFound(SimpleNode node, Found found) {
    	ASTEntry parent = popParent(node);
		return checkFound(found, parent);
    }

    
	private Found checkFound(Found found, ASTEntry parent) {
		if(found == null){
			return null;
		}
		List<GenAndTok> all = found.getAll();
		
		int absoluteCursorOffset = ps.getAbsoluteCursorOffset();
		try {
			IRegion region = document.getLineInformationOfOffset(absoluteCursorOffset);
			int currLine = document.getLineOfOffset(absoluteCursorOffset);
			int currCol = absoluteCursorOffset - region.getOffset();
			
			for (GenAndTok gen : all) {
				for (IToken tok2 : gen.getAllTokens()) {
					if(checkToken(found, currLine, currCol, tok2, parent)){
						return found; //ok, found it
					}
				}
			}
		} catch (Exception e) {
			Log.log(e);
		}
		return null;
	}

	private boolean checkToken(Found found, int currLine, int currCol, IToken generator, ASTEntry parent) {
		int startLine = AbstractMessage.getStartLine(generator, this.document)-1;
		int endLine = AbstractMessage.getEndLine(generator, this.document, false)-1;
		
		int startCol = AbstractMessage.getStartCol(generator, this.document, generator.getRepresentation(), true)-1;
		int endCol = AbstractMessage.getEndCol(generator, this.document, generator.getRepresentation(), false)-1;
		if(currLine >= startLine && currLine <= endLine && currCol >= startCol && currCol <= endCol){
			//ok, it's a valid occurrence, so, let's add it.
			foundOccurrences.add(new Tuple3<Found, Integer, ASTEntry>(found, currCol-startCol, parent));
			return true;
		}
		return false;
	}
    

	/**
	 * @return all the token occurrences
	 */
	public List<IToken> getTokenOccurrences() {
		List<IToken> ret = new ArrayList<IToken>();
		
		List<ASTEntry> entryOccurrences = getEntryOccurrences();
		for (ASTEntry entry : entryOccurrences) {
			ret.add(AbstractVisitor.makeToken(entry.node, moduleName));
		}
		return ret;
	}
	
	/**
	 * We get the occurrences as tokens for the name we're looking for. Note that the complete name (may be a dotted name)
	 * we're looking for may not be equal to the 'partial' name.
	 * 
	 * This can happen when we're looking for some import such as os.path, and are looking just for the 'path' part.
	 * So, when this happens, the return is analyzed and only returns names as the one we're looking for (with
	 * the correct line and col positions). 
	 */
	public List<ASTEntry> getEntryOccurrences() {
		checkFinished();
        Set<Tuple3<String, Integer, Integer>> s = new HashSet<Tuple3<String, Integer, Integer>>(); 
		
		ArrayList<Tuple3<IToken, Integer, ASTEntry>> complete = getCompleteTokenOccurrences();
		ArrayList<ASTEntry> ret = new ArrayList<ASTEntry>();
		
		for (Tuple3<IToken, Integer, ASTEntry> tup: complete) {
			IToken token = tup.o1;
			
			//if it is different, we have to make partial names
			SourceToken sourceToken = (SourceToken)tup.o1;
			SimpleNode ast = (sourceToken).getAst();
			
			String representation = null;
			
			if(ast instanceof ImportFrom){
				ImportFrom f = (ImportFrom) ast;
				//f.names may be empty if it is a wild import
				for (aliasType t : f.names){
					NameTok importName = NodeUtils.getNameForAlias(t);
					String importRep = NodeUtils.getFullRepresentationString(importName);
					
					if(importRep.equals(nameToFind)){
						ast = importName;
						representation = importRep;
						break;
					}
					
				}
				
			}else if(ast instanceof Import){
				representation = NodeUtils.getFullRepresentationString(ast);
				Import f = (Import) ast;
				NameTok importName = NodeUtils.getNameForRep(f.names, representation);
				if(importName != null){
					ast = importName;
				}
				
				
			}else{
				representation = NodeUtils.getFullRepresentationString(ast);
			}
			
			if(representation == null){
				continue; //can happen on wild imports
			}
			if(nameToFind.equals(representation)){
				ret.add(new ASTEntry(tup.o3, ast));
				continue;
			}
            if(!FullRepIterable.containsPart(representation, nameToFind)){
                continue;
            }
			
			Name nameAst = new Name(nameToFind, Name.Store);
			String[] strings = FullRepIterable.dotSplit(representation);
			
			int plus = 0;
			for (String string : strings) {
				if(string.equals(nameToFind) && (plus + nameToFind.length() >= tup.o2) ){
					break;
				}
				plus += string.length()+1; //len + dot
			}
			nameAst.beginColumn = AbstractMessage.getStartCol(token, ps.getDoc())+plus;
			nameAst.beginLine = AbstractMessage.getStartLine(token, ps.getDoc());
            Tuple3<String, Integer, Integer> t = new Tuple3<String, Integer, Integer>(nameToFind, nameAst.beginColumn, nameAst.beginLine);
            if (!s.contains(t)){
                s.add(t);
                ret.add(new ASTEntry(tup.o3, nameAst));
            }
		}
		
		return ret;
	}

	/**
	 * @return all the occurrences found in a 'complete' way (dotted name).
	 */
	private ArrayList<Tuple3<IToken, Integer, ASTEntry>> getCompleteTokenOccurrences() {
		//that's because we don't want duplicates
		Set<Tuple<IToken, Integer>> f = new HashSet<Tuple<IToken, Integer>>();
		
		ArrayList<Tuple3<IToken, Integer, ASTEntry>> ret = new ArrayList<Tuple3<IToken, Integer, ASTEntry>>();
		
		for (Tuple3<Found, Integer, ASTEntry> found : foundOccurrences) {
			
			List<GenAndTok> all = found.o1.getAll();
			
			for (GenAndTok tok : all) {
				
				Tuple<IToken, Integer> tup = new Tuple<IToken, Integer>(tok.generator, found.o2);
				Tuple3<IToken, Integer, ASTEntry> tup3 = new Tuple3<IToken, Integer, ASTEntry>(tok.generator, found.o2, found.o3);
				
				if(!f.contains(tup)){
					f.add(tup);
					ret.add(tup3);
				}
				
				for (IToken t: tok.references){
					tup = new Tuple<IToken, Integer>(t, found.o2);
					tup3 = new Tuple3<IToken, Integer, ASTEntry>(t, found.o2, found.o3);
					if(!f.contains(tup)){
						f.add(tup);
						ret.add(tup3);
					}
				}
			}
		}
		return ret;
	}
	
	
	
}
