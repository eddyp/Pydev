package org.python.pydev.debug.model;

import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.internal.ui.views.console.ProcessConsole;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.internal.console.IOConsolePartition;
import org.eclipse.ui.views.properties.IPropertySource;
import org.eclipse.ui.views.tasklist.ITaskListResourceAdapter;
import org.python.pydev.core.ExtensionHelper;
import org.python.pydev.core.Tuple;
import org.python.pydev.core.docutils.StringUtils;
import org.python.pydev.core.log.Log;
import org.python.pydev.core.structure.FastStringBuffer;
import org.python.pydev.debug.core.IConsoleInputListener;
import org.python.pydev.debug.core.PydevDebugPlugin;
import org.python.pydev.debug.core.PydevDebugPrefs;
import org.python.pydev.debug.model.remote.AbstractDebuggerCommand;
import org.python.pydev.debug.model.remote.AbstractRemoteDebugger;
import org.python.pydev.debug.model.remote.RemoveBreakpointCommand;
import org.python.pydev.debug.model.remote.RunCommand;
import org.python.pydev.debug.model.remote.SendPyExceptionCommand;
import org.python.pydev.debug.model.remote.SetBreakpointCommand;
import org.python.pydev.debug.model.remote.ThreadListCommand;
import org.python.pydev.debug.model.remote.VersionCommand;
import org.python.pydev.debug.ui.launching.PythonRunnerConfig;
import org.python.pydev.plugin.PydevPlugin;

/**
 * This is the target for the debug (
 *
 * @author Fabio
 */
@SuppressWarnings("restriction")
public abstract class AbstractDebugTarget extends AbstractDebugTargetWithTransmission implements IDebugTarget, ILaunchListener {
    
    private static final boolean DEBUG = false;

    /**
     * Path pointing to the file that started the debug (e.g.: file with __name__ == '__main__') 
     */
    protected IPath[] file;
    
    /**
     * The threads found in the debugger.
     */
    protected PyThread[] threads;
    
    /**
     * Indicates whether we've already disconnected from the debugger.
     */
    protected boolean disconnected = false;
    
    /**
     * This is the instance used to pass messages to the debugger.
     */
    protected AbstractRemoteDebugger debugger;    
    
    /**
     * Launch that triggered the debug session.
     */
    protected ILaunch launch;
    
    /**
     * Class used to check for modifications in the values already found.
     */
    private ValueModificationChecker modificationChecker;

    private PyRunToLineTarget runToLineTarget;

    public AbstractDebugTarget() {
        modificationChecker = new ValueModificationChecker();
    }
    
    public ValueModificationChecker getModificationChecker(){
        return modificationChecker;
    }
    
    public abstract boolean canTerminate();
    public abstract boolean isTerminated();
    
    public void terminate(){
    
        if (socket != null) {
            try {
                socket.shutdownInput(); // trying to make my pydevd notice that the socket is gone
            } catch (Exception e) {
                // ok, ignore
            }
            try {
                socket.shutdownOutput(); 
            } catch (Exception e) {
                // ok, ignore
            }    
            try {
                socket.close();
            } catch (Exception e) {
                // ok, ignore
            }
        }
        socket = null;
        disconnected = true;
        
    
        if (writer != null) {
            writer.done();
            writer = null;
        }
        if (reader != null) {
            reader.done();
            reader = null;
        }
        
        if(DEBUG){
            System.out.println( "TERMINATE" );
        }

        
        threads = new PyThread[0];
        fireEvent(new DebugEvent(this, DebugEvent.TERMINATE));

    }
    
    public AbstractRemoteDebugger getDebugger() {
        return debugger;
    }
    
    
    public void launchAdded(ILaunch launch) {
        // noop
    }

    public void launchChanged(ILaunch launch) {
        // noop        
    }
    
    // From IDebugElement
    public String getModelIdentifier() {
        return PyDebugModelPresentation.PY_DEBUG_MODEL_ID;
    }
    // From IDebugElement
    public IDebugTarget getDebugTarget() {
        return this;
    }    
    
    public String getName() throws DebugException {
        if (file != null){
            return PythonRunnerConfig.getRunningName(file);
        }else{
            return "unknown"; //TODO: SHOW PROPER PROCESS ID!
        }
    }
    
    public boolean canResume() {
        for (int i=0; i< threads.length; i++){
            if (threads[i].canResume()){
                return true;
            }
        }
        return false;
    }

    public boolean canSuspend() {
        for (int i=0; i< threads.length; i++){
            if (threads[i].canSuspend()){
                return true;
            }
        }
        return false;
    }

    public boolean isSuspended() {
        return false;
    }

    public void resume() throws DebugException {
        for (int i=0; i< threads.length; i++)
            threads[i].resume();
    }

    public void suspend() throws DebugException {
        for (int i=0; i< threads.length; i++){
            threads[i].suspend();
        }
    }
    
    public IThread[] getThreads() throws DebugException {
        if (debugger == null){
            return null;
        }
        
        if (threads == null) {
            ThreadListCommand cmd = new ThreadListCommand(this);
            this.postCommand(cmd);
            try {
                cmd.waitUntilDone(1000);
                threads = cmd.getThreads();
            } catch (InterruptedException e) {
                threads = new PyThread[0];
            }
        }
        return threads;
    }

    public boolean hasThreads() throws DebugException {
        return true;
    }

    //Breakpoints ------------------------------------------------------------------------------------------------------
    /**
     * @return true if the given breakpoint is supported by this target
     */
    public boolean supportsBreakpoint(IBreakpoint breakpoint) {
        return breakpoint instanceof PyBreakpoint;
    }
    
    /**
     * @return true if all the breakpoints should be skipped. Patch from bug: 
     * http://sourceforge.net/tracker/index.php?func=detail&aid=1960983&group_id=85796&atid=577329
     */
    private boolean shouldSkipBreakpoints() {
        DebugPlugin manager= DebugPlugin.getDefault();
        return manager != null && !manager.getBreakpointManager().isEnabled();
    }
    
    /**
     * Adds a breakpoint if it's enabled.
     */
    public void breakpointAdded(IBreakpoint breakpoint) {
        try {
            if (breakpoint instanceof PyBreakpoint) {
                PyBreakpoint b = (PyBreakpoint)breakpoint;
                if (b.isEnabled() && !shouldSkipBreakpoints()) {
                    String condition = b.getCondition();
                    if(condition != null){
                        condition = StringUtils.replaceAll(condition, "\n", "@_@NEW_LINE_CHAR@_@");
                        condition = StringUtils.replaceAll(condition, "\t", "@_@TAB_CHAR@_@");
                    }
                    SetBreakpointCommand cmd = new SetBreakpointCommand(
                            this, b.getFile(), b.getLine(), condition, b.getFunctionName());
                    this.postCommand(cmd);
                }
            }
        } catch (CoreException e) {
            PydevPlugin.log(e);
        }
    }

    /**
     * Removes an existing breakpoint from the debug target.
     */
    public void breakpointRemoved(IBreakpoint breakpoint, IMarkerDelta delta) {
        if (breakpoint instanceof PyBreakpoint) {
            PyBreakpoint b = (PyBreakpoint)breakpoint;
            RemoveBreakpointCommand cmd = new RemoveBreakpointCommand(this, b.getFile(), b.getLine());
            this.postCommand(cmd);
        }
    }

    /**
     * Called when a breakpoint is changed. 
     * E.g.: 
     *  - When line numbers change in the file
     *  - When the manager decides to enable/disable all existing markers
     *  - When the breakpoint properties (hit condition) are edited
     */
    public void breakpointChanged(IBreakpoint breakpoint, IMarkerDelta delta) {
        if (breakpoint instanceof PyBreakpoint) {
            breakpointRemoved(breakpoint, null);
            breakpointAdded(breakpoint);
        }
    }
    
    
    //End Breakpoints --------------------------------------------------------------------------------------------------

    
    // Storage retrieval is not supported
    public boolean supportsStorageRetrieval() {
        return false;
    }

    public IMemoryBlock getMemoryBlock(long startAddress, long length) throws DebugException {
        return null;
    }    

    /**
     * When a command that originates from daemon is received,
     * this routine processes it.
     * The responses to commands originating from here
     * are processed by commands themselves
     */
    public void processCommand(String sCmdCode, String sSeqCode, String payload) {
        if(DEBUG){
            System.out.println("process command:" + sCmdCode+"\tseq:"+sSeqCode+"\tpayload:"+payload+"\n\n");
        }
        try {
            int cmdCode = Integer.parseInt(sCmdCode);
            
            if (cmdCode == AbstractDebuggerCommand.CMD_THREAD_CREATED){
                processThreadCreated(payload);
                
            }else if (cmdCode == AbstractDebuggerCommand.CMD_THREAD_KILL){
                processThreadKilled(payload);
                
            }else if (cmdCode == AbstractDebuggerCommand.CMD_THREAD_SUSPEND){
                processThreadSuspended(payload);
                
            }else if (cmdCode == AbstractDebuggerCommand.CMD_THREAD_RUN){
                processThreadRun(payload);
                
            }else{
                PydevDebugPlugin.log(IStatus.WARNING, "Unexpected debugger command:" + sCmdCode+"\nseq:"+sSeqCode+"\npayload:"+payload, null);
            }
        } catch (Exception e) {
            PydevDebugPlugin.log(IStatus.ERROR, "Error processing: " + sCmdCode+"\npayload: "+payload, e); 
        }    
    }

    protected void fireEvent(DebugEvent event) {
        DebugPlugin manager= DebugPlugin.getDefault();
        if (manager != null) {
            manager.fireDebugEventSet(new DebugEvent[]{event});
        }
    }

    /**
     * @return an existing thread with a given id (null if none)
     */
    protected PyThread findThreadByID(String thread_id)  {        
        for (IThread thread : threads){
            if (thread_id.equals(((PyThread)thread).getId())){
                return (PyThread)thread;
            }
        }
        return null;
    }
    
    /**
     * Add it to the list of threads
     */
    private void processThreadCreated(String payload) {
        
        PyThread[] newThreads;
        try {
            newThreads = XMLUtils.ThreadsFromXML(this, payload);
        } catch (CoreException e) {
            PydevDebugPlugin.errorDialog("Error in processThreadCreated", e);
            return;
        }

        // Hide Pydevd threads if requested
        if (PydevDebugPrefs.getPreferences().getBoolean(PydevDebugPrefs.HIDE_PYDEVD_THREADS)) {
            int removeThisMany = 0;
            
            for (int i=0; i< newThreads.length; i++){
                if (((PyThread)newThreads[i]).isPydevThread()){
                    removeThisMany++;
                }
            }
            
            if (removeThisMany > 0) {
                int newSize = newThreads.length - removeThisMany;
                
                if (newSize == 0){    // no threads to add
                    return;
                    
                } else {
                    
                    PyThread[] newnewThreads = new PyThread[newSize];
                    int i = 0;
                    
                    for (PyThread newThread: newThreads){
                        if (!((PyThread)newThread).isPydevThread()){
                            newnewThreads[i] = newThread;
                            i += 1;
                        }
                    }
                    
                    newThreads = newnewThreads;
                    
                }
            }
        }

        // add threads to the thread list, and fire event
        if (threads == null){
            threads = newThreads;
            
        } else {
            PyThread[] combined = new PyThread[threads.length + newThreads.length];
            int i = 0;
            for (i = 0; i < threads.length; i++){
                combined[i] = threads[i];
            }
            
            for (int j = 0; j < newThreads.length; i++, j++){
                combined[i] = newThreads[j];
            }
            threads = combined;
        }
        // Now notify debugger that new threads were added
        for (int i =0; i< newThreads.length; i++){ 
            fireEvent(new DebugEvent(newThreads[i], DebugEvent.CREATE));
        }
    }
    
    // Remote this from our thread list
    private void processThreadKilled(String thread_id) {
        PyThread threadToDelete = findThreadByID(thread_id);
        if (threadToDelete != null) {
            int j = 0;
            PyThread[] newThreads = new PyThread[threads.length - 1];
            for (int i=0; i < threads.length; i++){
                if (threads[i] != threadToDelete){ 
                    newThreads[j++] = threads[i];
                }
            }
            threads = newThreads;
            fireEvent(new DebugEvent(threadToDelete, DebugEvent.TERMINATE));
        }
    }

    private void processThreadSuspended(String payload) {
        Object[] threadNstack;
        try {
            threadNstack = XMLUtils.XMLToStack(this, payload);
        } catch (CoreException e) {
            PydevDebugPlugin.errorDialog("Error reading ThreadSuspended", e);
            return;
        }
        
        PyThread t = (PyThread)threadNstack[0];
        int reason = DebugEvent.UNSPECIFIED;
        String stopReason = (String) threadNstack[1];
        
        if (stopReason != null) {
            int stopReason_i = Integer.parseInt(stopReason);
            
            if (stopReason_i == AbstractDebuggerCommand.CMD_STEP_OVER ||
                stopReason_i == AbstractDebuggerCommand.CMD_STEP_INTO ||
                stopReason_i == AbstractDebuggerCommand.CMD_STEP_RETURN ||
                stopReason_i == AbstractDebuggerCommand.CMD_RUN_TO_LINE ||
                stopReason_i == AbstractDebuggerCommand.CMD_SET_NEXT_STATEMENT){
                reason = DebugEvent.STEP_END;
                
            }else if (stopReason_i == AbstractDebuggerCommand.CMD_THREAD_SUSPEND){
                reason = DebugEvent.CLIENT_REQUEST;
                
            }else if (stopReason_i == AbstractDebuggerCommand.CMD_SET_BREAK){
                reason = DebugEvent.BREAKPOINT;
                
            }else {
                PydevDebugPlugin.log(IStatus.ERROR, "Unexpected reason for suspension", null);
                reason = DebugEvent.UNSPECIFIED;
            }
        }
        if (t != null) {
            modificationChecker.onlyLeaveThreads((PyThread[]) this.threads);
            
            IStackFrame stackFrame[] = (IStackFrame[])threadNstack[2]; 
            t.setSuspended(true, stackFrame);
            fireEvent(new DebugEvent(t, DebugEvent.SUSPEND, reason));        
        }
    }
    
    

    
    /**
     * @param payload a string in the format: thread_id\tresume_reason
     * E.g.: pid3720_zad_seq1\t108
     *  
     * @return a tuple with the thread id and the reason it stopped.
     * @throws CoreException 
     */
    public static Tuple<String, String> getThreadIdAndReason(String payload) throws CoreException{
        List<String> split = StringUtils.split(payload.trim(), '\t');
        if(split.size() != 2){
            String msg = "Unexpected threadRun payload " + payload + "(unable to match)";
            throw new CoreException(PydevDebugPlugin.makeStatus(IStatus.ERROR, msg, new RuntimeException(msg)));
        }
        return new Tuple<String, String>(split.get(0), split.get(1));
    }
    
    /**
     * ThreadRun event processing
     */
    private void processThreadRun(String payload) {
        try {
            Tuple<String, String> threadIdAndReason = getThreadIdAndReason(payload);
            int resumeReason = DebugEvent.UNSPECIFIED;
            try {
                int raw_reason = Integer.parseInt(threadIdAndReason.o2);
                if (raw_reason == AbstractDebuggerCommand.CMD_STEP_OVER)
                    resumeReason = DebugEvent.STEP_OVER;
                else if (raw_reason == AbstractDebuggerCommand.CMD_STEP_RETURN)
                    resumeReason = DebugEvent.STEP_RETURN;
                else if (raw_reason == AbstractDebuggerCommand.CMD_STEP_INTO)
                    resumeReason = DebugEvent.STEP_INTO;
                else if (raw_reason == AbstractDebuggerCommand.CMD_RUN_TO_LINE)
                    resumeReason = DebugEvent.UNSPECIFIED;
                else if (raw_reason == AbstractDebuggerCommand.CMD_SET_NEXT_STATEMENT)
                    resumeReason = DebugEvent.UNSPECIFIED;
                else if (raw_reason == AbstractDebuggerCommand.CMD_THREAD_RUN)
                    resumeReason = DebugEvent.CLIENT_REQUEST;
                else {
                    PydevDebugPlugin.log(IStatus.ERROR, "Unexpected resume reason code", null);
                    resumeReason = DebugEvent.UNSPECIFIED;
                }                
            }
            catch (NumberFormatException e) {
                // expected, when pydevd reports "None"
                resumeReason = DebugEvent.UNSPECIFIED;
            }
            
            String threadID = threadIdAndReason.o1;
            PyThread t = (PyThread)findThreadByID(threadID);
            if (t != null) {
                t.setSuspended(false, null);
                fireEvent(new DebugEvent(t, DebugEvent.RESUME, resumeReason));
                
            }else{
                FastStringBuffer buf = new FastStringBuffer();
                for(PyThread thread:threads){
                    if(buf.length() > 0){
                        buf.append(", ");
                    }
                    buf.append("id: "+thread.getId());
                }
                String msg = "Unable to find thread: " + threadID+ " available: "+buf;
                PydevDebugPlugin.log(IStatus.ERROR, msg, new RuntimeException(msg));
            }
        } catch (CoreException e1) {
            Log.log(e1);
        }

    }
    
    /**
     * Called after debugger has been connected.
     *
     * Here we send all the initialization commands
     * and exceptions on which pydev debugger needs to break
     */
	public void initialize() {
        // we post version command just for fun
        // it establishes the connection
        this.postCommand(new VersionCommand(this));

        // now, register all the breakpoints in all projects
        addBreakpointsFor(ResourcesPlugin.getWorkspace().getRoot());

        // Sending python exceptions sending run command 
        SendPyExceptionCommand sendCmd = new SendPyExceptionCommand(this, AbstractDebuggerCommand.CMD_SEND_PY_EXCEPTION);
        this.postCommand(sendCmd);

        // Send the run command, and we are off
        RunCommand run = new RunCommand(this);
        this.postCommand(run);
    }

    /**
     * Adds the breakpoints associated with a container.
     * @param container the container we're interested in (usually workspace root)
     */
    private void addBreakpointsFor(IContainer container) {
        try {
            IMarker[] markers = container.findMarkers(PyBreakpoint.PY_BREAK_MARKER, true, IResource.DEPTH_INFINITE);
            IMarker[] condMarkers = container.findMarkers(PyBreakpoint.PY_CONDITIONAL_BREAK_MARKER, true, IResource.DEPTH_INFINITE);
            IBreakpointManager breakpointManager = DebugPlugin.getDefault().getBreakpointManager();
            
            for (IMarker marker : markers) {
                PyBreakpoint brk = (PyBreakpoint) breakpointManager.getBreakpoint(marker);
                breakpointAdded(brk);
            }
            
            for (IMarker marker: condMarkers) {
                PyBreakpoint brk = (PyBreakpoint) breakpointManager.getBreakpoint(marker);
                breakpointAdded(brk);
            }
        } catch (Throwable t) {
            PydevDebugPlugin.errorDialog("Error setting breakpoints", t);
        }
    }
    
    /**
     * This function adds the input listener extension point, so that plugins that only care about
     * the input in the console can know about it.
     */
    @SuppressWarnings({ "unchecked" })
    public void addConsoleInputListener(){
        IConsole console = DebugUITools.getConsole(this.getProcess());
        if (console instanceof ProcessConsole) {
            final ProcessConsole c = (ProcessConsole) console;
            final List<IConsoleInputListener> participants = ExtensionHelper.getParticipants(ExtensionHelper.PYDEV_DEBUG_CONSOLE_INPUT_LISTENER);
            final AbstractDebugTarget target = this;
            //let's listen the doc for the changes
            c.getDocument().addDocumentListener(new IDocumentListener(){

                public void documentAboutToBeChanged(DocumentEvent event) {
                    //only report when we have a new line
                    if(event.fText.indexOf('\r') != -1 || event.fText.indexOf('\n') != -1){
                        try {
                            ITypedRegion partition = event.fDocument.getPartition(event.fOffset);
                            if(partition instanceof IOConsolePartition){
                                IOConsolePartition p = (IOConsolePartition) partition;
                                
                                //we only communicate about inputs (because we only care about what the user writes)
                                if(p.getType().equals(IOConsolePartition.INPUT_PARTITION_TYPE)){
                                    if(event.fText.length() <= 2){
                                        //the user typed something
                                        final String inputFound = p.getString();
                                        for (IConsoleInputListener listener : participants) {
                                            listener.newLineReceived(inputFound, target);
                                        }
                                    }
                                    
                                }
                            }
                        } catch (Exception e) {
                            Log.log(e);
                        }
                    }
                    
                }

                public void documentChanged(DocumentEvent event) {
                    //only report when we have a new line
                    if(event.fText.indexOf('\r') != -1 || event.fText.indexOf('\n') != -1){
                        try {
                            ITypedRegion partition = event.fDocument.getPartition(event.fOffset);
                            if(partition instanceof IOConsolePartition){
                                IOConsolePartition p = (IOConsolePartition) partition;
                                
                                //we only communicate about inputs (because we only care about what the user writes)
                                if(p.getType().equals(IOConsolePartition.INPUT_PARTITION_TYPE)){
                                    if(event.fText.length() > 2){
                                        //the user pasted something
                                        for (IConsoleInputListener listener : participants) {
                                            listener.pasteReceived(event.fText, target);
                                        }
                                    }
                                    
                                }
                            }
                        } catch (Exception e) {
                            Log.log(e);
                        }
                    }
                }
                
            });
        }
    }

    
    public boolean canDisconnect() {
        return !disconnected;
    }

    public void disconnect() throws DebugException {
        this.terminate();
        modificationChecker = null;
    }

    public boolean isDisconnected() {
        return disconnected;
    }
    
    public Object getAdapter(Class adapter) {        
        AdapterDebug.print(this, adapter);
        
        // Not really sure what to do here, but I am trying
        if (adapter.equals(ILaunch.class)){
            return launch;
            
        }else if (adapter.equals(IResource.class)) {
            // used by Variable ContextManager, and Project:Properties menu item
            if( file!=null ) {
                IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocation(file[0]);
                
                if (files != null && files.length > 0){
                    return files[0];

                }else{
                    return null;
                    
                }
            }
            
        } else if (adapter.equals(org.eclipse.debug.ui.actions.IRunToLineTarget.class)){
            return this.getRunToLineTarget();
            
            
        } else if (adapter.equals(IPropertySource.class)){
            return launch.getAdapter(adapter);
            
        } else if (adapter.equals(ITaskListResourceAdapter.class) 
                || adapter.equals(org.eclipse.debug.ui.actions.IToggleBreakpointsTarget.class) 
                ){
            return  super.getAdapter(adapter);
        }
        
        AdapterDebug.printDontKnow(this, adapter);
        return super.getAdapter(adapter);
    }

    
    public PyRunToLineTarget getRunToLineTarget(){
        if(this.runToLineTarget == null){
            this.runToLineTarget = new PyRunToLineTarget();
        }
        return this.runToLineTarget;
    }

    //From IDebugElement
    public ILaunch getLaunch() {
        return launch;
    }   
    
    
}
