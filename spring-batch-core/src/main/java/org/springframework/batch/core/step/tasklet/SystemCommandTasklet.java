package org.springframework.batch.core.step.tasklet;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import org.apache.commons.lang.time.StopWatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.listener.StepExecutionListenerSupport;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.util.Assert;

/**
 * {@link Tasklet} that executes a system command.
 * 
 * The system command is executed asynchronously using injected
 * {@link #setTaskExecutor(TaskExecutor)} - timeout value is required to be set,
 * so that the batch job does not hang forever if the external process hangs.
 * 
 * Tasklet periodically checks for termination status (i.e.
 * {@link #setCommand(String)} finished its execution or
 * {@link #setTimeout(long)} expired or job was interrupted). The check interval
 * is given by {@link #setTerminationCheckInterval(long)}.
 * 
 * When job interrupt is detected tasklet's execution is terminated immediately
 * by throwing {@link JobInterruptedException}.
 * 
 * {@link #setInterruptOnCancel(boolean)} specifies whether the tasklet should
 * attempt to interrupt the thread that executes the system command if it is
 * still running when tasklet exits (abnormally).
 * 
 * @author Robert Kasanicky
 */
public class SystemCommandTasklet extends StepExecutionListenerSupport implements Tasklet, InitializingBean {

	protected static final Log logger = LogFactory.getLog(SystemCommandTasklet.class);

	private String command;

	private String[] environmentParams = null;

	private File workingDirectory = null;

	private SystemProcessExitCodeMapper systemProcessExitCodeMapper = new SimpleSystemProcessExitCodeMapper();

	private long timeout = 0;

	private long checkInterval = 1000;

	private StepExecution execution = null;

	private TaskExecutor taskExecutor = new SimpleAsyncTaskExecutor();

	private boolean interruptOnCancel = false;

	/**
	 * Execute system command and map its exit code to {@link ExitStatus} using
	 * {@link SystemProcessExitCodeMapper}.
	 */
	public RepeatStatus execute(StepContribution contribution, AttributeAccessor attributes) throws Exception {

		FutureTask<Integer> systemCommandTask = new FutureTask<Integer>(new Callable<Integer>() {

			public Integer call() throws Exception {
				Process process = Runtime.getRuntime().exec(command, environmentParams, workingDirectory);
				return process.waitFor();
			}

		});

		StopWatch stopWatch = new StopWatch();
		stopWatch.start();

		taskExecutor.execute(systemCommandTask);

		try {
			while (true) {
				Thread.sleep(checkInterval);
				if (systemCommandTask.isDone()) {
					contribution.setExitStatus(systemProcessExitCodeMapper.getExitStatus(systemCommandTask.get()));
					return RepeatStatus.FINISHED;
				}
				else if (stopWatch.getTime() > timeout) {
					systemCommandTask.cancel(interruptOnCancel);
					throw new SystemCommandException("Execution of system command did not finish within the timeout");
				}
				else if (execution.isTerminateOnly()) {
					systemCommandTask.cancel(interruptOnCancel);
					throw new JobInterruptedException("Job interrupted while executing system command '" + command
							+ "'");
				}
			}
		}
		finally {
			stopWatch.stop();
		}

	}

	/**
	 * @param command command to be executed in a separate system process
	 */
	public void setCommand(String command) {
		this.command = command;
	}

	/**
	 * @param envp environment parameter values, inherited from parent process
	 * when not set (or set to null).
	 */
	public void setEnvironmentParams(String[] envp) {
		this.environmentParams = envp;
	}

	/**
	 * @param dir working directory of the spawned process, inherited from
	 * parent process when not set (or set to null).
	 */
	public void setWorkingDirectory(String dir) {
		if (dir == null) {
			this.workingDirectory = null;
			return;
		}
		this.workingDirectory = new File(dir);
		Assert.isTrue(workingDirectory.exists(), "working directory must exist");
		Assert.isTrue(workingDirectory.isDirectory(), "working directory value must be a directory");

	}

	public void afterPropertiesSet() throws Exception {
		Assert.hasLength(command, "'command' property value is required");
		Assert.notNull(systemProcessExitCodeMapper, "SystemProcessExitCodeMapper must be set");
		Assert.isTrue(timeout > 0, "timeout value must be greater than zero");
		Assert.notNull(taskExecutor, "taskExecutor is required");
	}

	/**
	 * @param systemProcessExitCodeMapper maps system process return value to
	 * <code>ExitStatus</code> returned by Tasklet.
	 * {@link SimpleSystemProcessExitCodeMapper} is used by default.
	 */
	public void setSystemProcessExitCodeMapper(SystemProcessExitCodeMapper systemProcessExitCodeMapper) {
		this.systemProcessExitCodeMapper = systemProcessExitCodeMapper;
	}

	/**
	 * @param timeout upper limit for how long the execution of the external
	 * program is allowed to last.
	 */
	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}

	/**
	 * The time interval how often the tasklet will check for termination
	 * status.
	 * 
	 * @param checkInterval time interval in milliseconds (1 second by default).
	 */
	public void setTerminationCheckInterval(long checkInterval) {
		this.checkInterval = checkInterval;
	}

	/**
	 * Get a reference to {@link StepExecution} for interrupt checks during
	 * system command execution.
	 */
	@Override
	public void beforeStep(StepExecution stepExecution) {
		this.execution = stepExecution;
	}

	/**
	 * Sets the task executor that will be used to execute the system command
	 * NB! Avoid using a synchronous task executor
	 */
	public void setTaskExecutor(TaskExecutor taskExecutor) {
		this.taskExecutor = taskExecutor;
	}

	/**
	 * If <code>true</code> tasklet will attempt to interrupt the thread
	 * executing the system command if {@link #setTimeout(long)} has been
	 * exceeded or user interrupts the job. <code>false</code> by default
	 */
	public void setInterruptOnCancel(boolean interruptOnCancel) {
		this.interruptOnCancel = interruptOnCancel;
	}

}
