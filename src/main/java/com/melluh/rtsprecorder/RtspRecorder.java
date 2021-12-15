package com.melluh.rtsprecorder;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import com.melluh.rtsprecorder.http.WebServer;
import com.melluh.rtsprecorder.task.CleanupRecordingsTask;
import com.melluh.rtsprecorder.task.MoveRecordingsTask;
import com.melluh.rtsprecorder.task.WatchdogTask;

public class RtspRecorder {

	static {
		System.setProperty("java.util.logging.manager", CustomLogManager.class.getName());
	}
	
	public static final Logger LOGGER = Logger.getLogger("rtsp-recorder");
	private static RtspRecorder instance;
	
	private final ExecutorService threadPool = Executors.newCachedThreadPool();
	
	private CameraRegistry cameraRegistry;
	private ConfigHandler configHandler;
	private Database database;
	private WebServer webServer;
	
	private void start() {
		LOGGER.info("Starting rtsp-recorder...");
		
		this.cameraRegistry = new CameraRegistry();
		this.configHandler = new ConfigHandler();
		
		if(!configHandler.load()) {
			LOGGER.severe("Config read failed, exiting.");
			return;
		}
		
		LOGGER.info("Finishing loading config, " + cameraRegistry.getNumCameras() + " camera(s) defined");
		LOGGER.info("Recordings will be stored in " + configHandler.getRecordingsFolder().getAbsolutePath());
		
		this.database = new Database();
		database.connect();
		
		this.webServer = new WebServer();
		webServer.start();
		
		LOGGER.info("Starting FFmpeg processes...");
		Runtime.getRuntime().addShutdownHook(new ShutdownHook());
		cameraRegistry.getCameras().forEach(camera -> camera.getProcess().start());
		LOGGER.info("FFmpeg processed started.");
		
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
		executor.scheduleAtFixedRate(new WatchdogTask(), 1, 1, TimeUnit.SECONDS);
		executor.scheduleAtFixedRate(new MoveRecordingsTask(), 0, 5, TimeUnit.MINUTES);
		executor.scheduleAtFixedRate(new CleanupRecordingsTask(), 0, 30, TimeUnit.MINUTES);
		LOGGER.info("Tasks initialized.");
	}
	
	private class ShutdownHook extends Thread {
		
		@Override
		public void run() {
			LOGGER.info("Stopping FFmpeg processes...");
			cameraRegistry.getCameras().forEach(camera -> camera.getProcess().stop());
			
			LOGGER.info("Goodbye!");
			CustomLogManager.resetFinally();
		}
		
	}
	
	public CameraRegistry getCameraRegistry() {
		return cameraRegistry;
	}
	
	public ConfigHandler getConfigHandler() {
		return configHandler;
	}

	public Database getDatabase() {
		return database;
	}
	
	public ExecutorService getThreadPool() {
		return threadPool;
	}
	
	public static void main(String[] args) {
		ConsoleHandler handler = new ConsoleHandler();
		handler.setFormatter(new SimpleFormatter() {
			private static final String FORMAT = "[%1$tF %1$tT] [%2$s] %3$s %n";
			
			@Override
			public synchronized String format(LogRecord record) {
				return String.format(FORMAT, new Date(record.getMillis()), record.getLevel().getName(), record.getMessage());
			}
		});
		LOGGER.setUseParentHandlers(false);
		LOGGER.addHandler(handler);
		
		instance = new RtspRecorder();
		instance.start();
	}
	
	// this is needed to make sure logging keeps working in the shutdown hook
	public static class CustomLogManager extends LogManager {
		
		private static CustomLogManager instance;
		
		public CustomLogManager() {
			instance = this;
		}
		
		@Override
		public void reset() throws SecurityException {}
		
		private void resetSuper() {
			super.reset();
		}
		
		public static void resetFinally() {
			instance.resetSuper();
		}
		
	}
	
	public static RtspRecorder getInstance() {
		return instance;
	}
}
