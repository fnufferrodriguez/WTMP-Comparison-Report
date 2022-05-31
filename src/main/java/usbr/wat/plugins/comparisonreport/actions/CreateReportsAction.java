/*
 * Copyright 2021  Hydrologic Engineering Center (HEC).
 * United States Army Corps of Engineers
 * All Rights Reserved.  HEC PROPRIETARY/CONFIDENTIAL.
 * Source may not be released without written approval
 * from HEC
 */
package usbr.wat.plugins.comparisonreport.actions;


import java.awt.EventQueue;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.python.core.PyCode;
import org.python.util.PythonInterpreter;

import com.rma.io.FileManagerImpl;
import com.rma.io.RmaFile;
import com.rma.model.Project;

import hec2.plugin.model.ModelAlternative;
import hec2.wat.model.WatSimulation;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExporter;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.data.JRXmlDataSource;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.engine.util.JRXmlUtils;
import net.sf.jasperreports.repo.FileRepositoryPersistenceServiceFactory;
import net.sf.jasperreports.repo.FileRepositoryService;
import net.sf.jasperreports.repo.PersistenceServiceFactory;
import net.sf.jasperreports.repo.RepositoryService;
import rma.util.RMAIO;
import usbr.wat.plugins.actionpanel.ActionPanelPlugin;
import usbr.wat.plugins.actionpanel.ActionsWindow;
import usbr.wat.plugins.actionpanel.actions.AbstractReportAction;
import usbr.wat.plugins.actionpanel.io.ReportOptions;
import usbr.wat.plugins.actionpanel.io.ReportXmlFile;
import usbr.wat.plugins.actionpanel.model.ReportsManager;
import usbr.wat.plugins.actionpanel.model.SimulationReportInfo;

/**
 * @author Mark Ackerman
 *
 */
@SuppressWarnings("serial")
public class CreateReportsAction extends AbstractReportAction
	
{
	
	public static final String REPORT_DIR = "reports";
	private static final String JASPER_REPORT_DIR = "Reports";
	private static final String JASPER_FILE = "USBR_Draft_Validation.jrxml";
	public static final String JASPER_OUT_FILE = "WTMP_report_draft-";
	public static final String REPORT_FILE_EXT = ".pdf";
	private static final String XML_DATA_DOCUMENT = "USBRAutomatedReportDataAdapter.xml";
	
	
	private static final String SCRIPTS_DIR = "scripts";
	
	private ActionsWindow _parent;
	
	private PythonInterpreter  _interp;
	private PyCode _pycode;
	
	public CreateReportsAction(ActionsWindow parent)
	{
		super("Create Reports");
		setEnabled(false);
		_parent = parent;
	}
	
	public boolean createReport(ReportOptions options)
	{
		
		WatSimulation sim;
		long t1 = System.currentTimeMillis();
		List<SimulationReportInfo> sims = _parent.getSimulationReportInfos();
		String xmlFile = createSimulationXmlFile(sims);
		if ( xmlFile != null )
		{
			if ( runPythonScript(xmlFile))
			{
				return runJasperReport(sims.get(0), options);
			}
		}
		return false;
	}
	
	/**
	 * @param sim
	 */
	private String createSimulationXmlFile(List<SimulationReportInfo> sims)
	{
		Project prj = Project.getCurrentProject();
		String studyDir = prj.getProjectDirectory();
		String filename = RMAIO.concatPath(studyDir, REPORT_DIR);
		filename = RMAIO.concatPath(filename, RMAIO.userNameToFileName(sims.get(0).getName()+"comparison")+".xml");
		if ( Boolean.getBoolean("SkipComparisonReportFile"))
		{
			return filename;
		}
		
		ReportXmlFile xmlFile = new ReportXmlFile(filename);
		xmlFile.setStudyInfo(studyDir, getObsDataPath(studyDir));
		List<SimulationReportInfo>simList = new ArrayList<>();
		simList.addAll(sims);
		xmlFile.setSimulationInfo(_parent.getSimulationGroup().getName(), sims);
		if (  xmlFile.createXMLFile() )
		{
			return filename;
		}
		return null;
	}
	
	
	
	/**
	 * run the bat file to create the XMl file that's input to the jasper report
	 * @param sim
	 * @return
	 */
	public boolean runPythonScript(WatSimulation sim, ModelAlternative modelAlt, String baseSimulationName)
	{
		
		
		// first run the python script through the .bat file
		// bat file needs: 
		// 1. watershed path
		// 2. simulation path
		// 3. model name ... i.e. ResSim
		// 4. alternative's F-Part
		// 5. folder to the observation data in the study
		// 6. alternative's name
		// 7. simulation's base name
		
		long t1 = System.currentTimeMillis();
		try
		{
			String fpart = findFpartForPython(sim, modelAlt);
			if ( fpart == null )
			{
				System.out.println("createReportAction:no ResSim Alternative found in Simulation "+sim);
				return false;
			}

			List<String>cmdList = new ArrayList<>();
			String dirToUse = getDirectoryToUse();
			String exeFile = RMAIO.concatPath(dirToUse, PYTHON_REPORT_BAT);
			String studyDir = Project.getCurrentProject().getProjectDirectory();
			//cmdList.add("cmd.exe");
			//cmdList.add("/c");
			cmdList.add(exeFile);
			cmdList.add(studyDir);
			// hack for having a comma in the path and the RMAIO.userNameToFileName() not catching it
			String simDir = sim.getSimulationDirectory();
			simDir = RMAIO.removeChar(simDir, ',');
			cmdList.add(simDir);
			cmdList.add(modelAlt.getProgram());
			cmdList.add(fpart);
			String obsPath = getObsDataPath(studyDir);
			cmdList.add(obsPath);
			cmdList.add(modelAlt.getName());
			cmdList.add(baseSimulationName);



			return runProcess(cmdList, dirToUse);
		}
		finally
		{
			long t2 = System.currentTimeMillis();
			System.out.println("runProcess:time to run python for "+sim+" alt "+modelAlt+" is "+(t2-t1)+"ms");
		}
		
	}
	
	
	
	
	
	/**
	 * @param sim
	 */
	public boolean runJasperReport(SimulationReportInfo sim, ReportOptions options)
	{
		long t1 = System.currentTimeMillis();
		try
		{
			//Log log = LogFactory.getLog(JasperFillManager.class);
			String studyDir = Project.getCurrentProject().getProjectDirectory();
			String simDir = sim.getSimFolder();
			String jasperRepoDir = RMAIO.concatPath(studyDir, REPORT_DIR);
			String rptFile = RMAIO.concatPath(jasperRepoDir, JASPER_FILE);
			//rptFile = RMAIO.concatPath(rptFile, JASPER_FILE);


			System.out.println("runReportWithOutputFile:report repository:"+jasperRepoDir);

			SimpleJasperReportsContext context = new SimpleJasperReportsContext();
			FileRepositoryService fileRepository = new FileRepositoryService(context, 
					jasperRepoDir, true);
			context.setExtensions(RepositoryService.class, Collections.singletonList(fileRepository));
			context.setExtensions(PersistenceServiceFactory.class, 
					Collections.singletonList(FileRepositoryPersistenceServiceFactory.getInstance()));
			String inJasperFile = rptFile;

			JRPropertiesUtil.getInstance(context).setProperty("net.sf.jasperreports.xpath.executer.factory",
					"net.sf.jasperreports.engine.util.xml.JaxenXPathExecuterFactory");



			long t2 = System.currentTimeMillis();
			JasperReport jasperReport;
			try
			{
				compileJasperFiles(RMAIO.getDirectoryFromPath(inJasperFile));
				jasperReport = JasperCompileManager.compileReport(inJasperFile);
			}
			catch (JRException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
				return false;
			}
			long t3 = System.currentTimeMillis();
			System.out.println("runJasperReport:time to compile jasper files for "+sim+ "is "+(t3-t2)+"ms");

			String outputFile = RMAIO.concatPath(simDir, REPORT_DIR);
			RmaFile simDirFile = FileManagerImpl.getFileManager().getFile(outputFile);
			if ( !simDirFile.exists() )
			{
				if ( !simDirFile.mkdirs())
				{
					System.out.println("runJasperReport:failed to create folder "+simDirFile.getAbsolutePath());
				}
			}
			outputFile = RMAIO.concatPath(outputFile, JASPER_OUT_FILE);

			Map<String, Object>params = new HashMap<>();
			// define the parameters for the report
			setParameters(params, jasperRepoDir, sim, options);
			
			
			
			
			SimpleDateFormat fmt= new SimpleDateFormat("yyyy.MM.dd-HHmm");
			
			Date date = new Date();
			outputFile = outputFile.concat(fmt.format(date));
			outputFile = outputFile.concat(REPORT_FILE_EXT);
			

			String xmlDataDoc = RMAIO.concatPath(studyDir, REPORT_DIR);
			xmlDataDoc = RMAIO.concatPath(xmlDataDoc, DATA_SOURCES_DIR);
			xmlDataDoc = RMAIO.concatPath(xmlDataDoc, XML_DATA_DOCUMENT);

			JasperPrint jasperPrint;
			System.out.println("runJasperReport:filling report "+inJasperFile);
			JRXmlDataSource dataSource;
			try
			{
				dataSource = new JRXmlDataSource(context, JRXmlUtils.parse(JRLoader.getLocationInputStream(xmlDataDoc)));
			}
			catch (JRException e1)
			{
				e1.printStackTrace();
				return false;
			}
			try
			{
				jasperPrint = JasperFillManager.getInstance(context).fill(jasperReport, params, dataSource);
			}
			catch (JRException e)
			{
				e.printStackTrace();
				return false;
			}
			long t4 = System.currentTimeMillis();
			System.out.println("runJasperReport:time to fill jasper report for "+sim+ "is "+(t4-t3)+"ms");

			// fills compiled report with parameters and a connection
			JRExporter exporter = options.getOutputType().buildExporter(jasperPrint, outputFile);
			//JRPdfExporter exporter = new JRPdfExporter();
			//exporter.setExporterInput(new SimpleExporterInput(jasperPrint));
			//exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(outputFile));

			try
			{
				exporter.exportReport();
				System.out.println("runJasperReport:report written to "+outputFile);
			}
			catch (JRException e)
			{
				e.printStackTrace();
				return false;
			}

			long t5 = System.currentTimeMillis();
			System.out.println("runJasperReport:time to write jasper report for "+sim+ "is "+(t5-t4)+"ms");
			return true;
		}
		finally
		{
			long end = System.currentTimeMillis();
			System.out.println("runJasperReport:total time to create jasper report for "+sim+" is "+(end-t1)+"ms");
		}
	}
	


	
	@Override
	public String getName()
	{
		return "Comparison Report";
	}
	@Override
	public String getDescription()
	{
		return "Comparison Report for multiple Simulations";
	}
	@Override
	public boolean isComparisonReport()
	{
		return false;
	}
	@Override
	public boolean isIterationReport()
	{
		return false;
	}
	
	@Override
	public String toString()
	{
		return getName();
	}
	public static void register()
	{
		ReportsManager.register(new CreateReportsAction(ActionPanelPlugin.getInstance().getActionsWindow()));
	}
	
	public static void main(String[] args)
	{
		EventQueue.invokeLater(()->register());
	}

}
