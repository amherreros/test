package com.fs.sondass7.web.be.services.logic;

import com.fs.sondass7.web.be.controllers.ControllerConstants;
import com.fs.sondass7.web.be.logic.adapters.ITransformer;
import com.fs.sondass7.web.be.logic.adapters.SondaXmlIdrResponseTransformer;
import com.fs.sondass7.web.be.logic.adapters.SondaXmlUlrResponseTransformer;
import com.fs.sondass7.web.be.logic.factories.XmlResponseTransformerFactory;
import com.fs.sondass7.web.be.logic.factories.XmlUnmarshallerFactory;
import com.fs.sondass7.web.be.model.domain.filelistener.SondaGenericXmlResponse;
import com.fs.sondass7.web.be.model.domain.filelistener.SondaXmlIdrResponse;
import com.fs.sondass7.web.be.model.domain.filelistener.SondaXmlResponse;
import com.fs.sondass7.web.be.model.domain.filelistener.SondaXmlUlrResponse;
import com.fs.sondass7.web.be.model.domain.filewriter.CsvWriterBulkQuery;
import com.fs.sondass7.web.be.model.domain.filewriter.SondaRequestEnum;
import com.fs.sondass7.web.be.model.domain.responses.ResponseErrorEnum;
import com.fs.sondass7.web.be.model.entities.*;
import com.fs.sondass7.web.be.model.exceptions.CryptoException;
import com.fs.sondass7.web.be.model.repositories.ISondaRequestJPARepository;
import com.fs.sondass7.web.be.model.repositories.ISondaRequestRepository;
import com.fs.sondass7.web.be.model.repositories.ISondaResultRepository;
import com.fs.sondass7.web.be.model.repositories.SondaResultJPARepository;
import com.fs.sondass7.web.be.services.ServiceParameters;
import com.fs.sondass7.web.be.services.cellLocation.api.ApiCellLocation;
import com.fs.sondass7.web.be.services.cellLocation.api.ApiCellLocationFactory;
import com.fs.sondass7.web.be.services.cellLocation.bean.CellLocationRequest;
import com.fs.sondass7.web.be.services.cellLocation.bean.CellLocationResponseObj;
import com.fs.sondass7.web.be.services.repository.*;
import com.fs.sondass7.web.be.utils.AesCryptoManager;
import com.fs.sondass7.web.be.utils.UuidUtils;
import com.fs.sondass7.web.be.utils.geohash.GeoHash;
import com.fs.sondass7.web.be.utils.mail.MailUtil;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;

import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.fs.sondass7.web.be.utils.ErrorConstants.*;

/**
 * Sonda XML Response Files Service
 *
 * @author pdelgado
 */
@Service
@Slf4j
public class ResponseFileListenerService {

    public static final String SRIFSM = "SRIFSM";
    public static final String PSI = "PSI";
    public static final String ATI = "ATI";
    public static final String IDR = "IDR";
    public static final String ULR = "ULR";
    private static final String DIAMETER = "Diameter";
    private static final String PAIR_DISALLOWED = "{} The pair mcc-mnc {}-{} is disallowed to be send.";
    private static final String RESTRICTION_MSG = "Una restricción impide lanzar la petición. Por favor, revisa los filtros de lista blanca y negra";
    public static final String STATE_0 = "0";
    public static final String STATE_1 = "1";
    public static final String PROCESSING_ERROR = "Error de procesamiento: ";


    /**
     * Sonda's Programming Data Service (Qrtz trigger description)
     */
    @Autowired
    private ProgrammingService programmingService;

    /**
     * Sonda Result Repository
     */
    @Autowired
    private ISondaResultRepository sondaResultRepository;

    @Autowired
    private SondaResultJPARepository sondaResultJPARepository;
    /**
     * Sonda Request Repository
     */
    @Autowired
    private ISondaRequestRepository sondaRequestRepository;

    @Autowired
    private ISondaRequestJPARepository sondaRequestJPARepository;

    /**
     * Application Configuration Service
     */
    private final AppConfigService appConfigService;

    /**
     * ImsiNodo Service
     */
    private final ImsiNodoService imsiNodoService;

    /**
     * Mme Service
     */
    private final MmeService mmeService;

    /**
     * Msc Service
     */
    private final MscService mscService;

    /**
     * Hss Service
     */
    private final HssService hssService;

    /**
     * ProcesarReglas Service
     */
    private final ProcesarReglasService procesarReglasService;

    /**
     * relMSISDN_IMSIService
     */
    private final RelMSISDN_IMSIService relMSISDN_IMSIService;

    /**
     * Unmarshaller for Generic SondaXmlResponse files
     */
    private Unmarshaller genericUnmarshaller;

    /**
     * Unmarshaller for specific SondaXmlResponse files
     */
    private Unmarshaller responseUnmarshaller;

    /**
     * Prefijos Repository Service
     */
    @Autowired
    private PrefijosService prefijosService;

    @Autowired
    private CountryCCService countryCCService;

    @Autowired
    private RestrictionsService restrictionsService;

    @Autowired
    private MailUtil mailUtil;

    @Autowired
    private OperatorService operatorService;

    /**
     * MasivasService Service
     */
    @Autowired
    private MasivasService masivasService;

    /**
     * MasivasReqService Service
     */
    @Autowired
    private MasivasReqService masivasReqService;

    @Autowired
    private ReqNodoService reqNodoService;

    /**
     * Custom Repository for dependency injection
     *
     * @param sondaResultRepository Sonda Result / Response Repository
     * @param appConfigService      Application Configuration Service
     * @param imsiNodoService
     * @param relMSISDN_imsiService
     */
    public ResponseFileListenerService(ISondaResultRepository sondaResultRepository, AppConfigService appConfigService, ImsiNodoService imsiNodoService, MmeService mmeService, MscService mscService, HssService hssService, ProcesarReglasService procesarReglasService, RelMSISDN_IMSIService relMSISDN_imsiService) {
        this.sondaResultRepository = sondaResultRepository;
        this.appConfigService = appConfigService;
        this.imsiNodoService = imsiNodoService;
        this.mmeService = mmeService;
        this.mscService = mscService;
        this.hssService = hssService;
        this.procesarReglasService = procesarReglasService;
        this.relMSISDN_IMSIService = relMSISDN_imsiService;
    }

    /**
     * Parses the received file to gets the {@link SondaResult} object and process it.
     *
     * @param file       Sonda XML (*.res) File
     * @param targetPath Directory to move the files that have been processed
     * @throws IOException   Unexpected
     * @throws JAXBException Unexpected
     */
    // request states (from results)
    // | SRI | PSI | INT |
    // |-----|-----|-----|
    // | -   | -   |   0 |
    // | OK  | -   |   1 |
    // | NOK | -   |  -1 |
    // | OK  | OK  |   2 |
    // | OK  | NOK |  -2 |
    // | OK  | NOK |  -4 | Para reintentar por otra tecnología si es posible
    public void processFile(File file, Path targetPath) throws IOException, JAXBException {

        String errMsg = "Se ha producido un error procesando el fichero de respuesta";
        boolean retraying = false;
        try {
            // Descifra el contenido del archivo
            errMsg = "Error decrypting file";
            log.info("Decrypting file");
            String decryptedContent = "";
            try{
                decryptedContent = decryptFile(file);
            }catch (Exception e){
                errMsg = FILE_DECRYPTED_ERROR;
                logErrAndException(FILE_DECRYPTED_ERROR, FILE_DECRYPTED_ERROR, e, true);
            }

            if (decryptedContent.contains("datetime")) {
                decryptedContent = decryptedContent.replace("datetime", "dateTime");
            }

            Reader baseReader = new StringReader(decryptedContent);
            String transactionId = UuidUtils.generateRandomUUID();
            String dateCodeName = "_" + System.currentTimeMillis();

            // Obtiene el tipo de respuesta base
            errMsg = "Error unmarshalling XML content";
            log.info("Unmarshalling XML content");
            SondaGenericXmlResponse baseResponse = (SondaGenericXmlResponse) getGenericXmlUnmarshaller().unmarshal(baseReader);
            log.info("{} Response '{}' -> {}", transactionId, file.getName(), baseResponse);

            // Obtiene el trId del archivo
            errMsg = "Error al obtener trId del archivo";
            log.info("Obteniendo trId del archivo");
            String trIdAux = baseResponse.getTrId();

            // Verifica el atributo obligatorio 'type'
            errMsg = "Error al verificar el atributo obligatorio 'type'";
            log.info("Verificando el atributo obligatorio 'type'");
            if (baseResponse.getType() != null) {

                // -- trId -- //
                try {
                    errMsg = "Error obteniendo el trId del fichero";
                    if(extractTrIdFromFile(decryptedContent) == null || "".equals(extractTrIdFromFile(decryptedContent))){
                        String idFromName = extractTrid(file.getName());
                        decryptedContent = replaceTrId(decryptedContent, idFromName);
                    }
                }catch (Exception e) {
                    errMsg = GET_TRID_ERROR;
                    logErrAndException(GET_TRID_ERROR, GET_TRID_ERROR, e, true);
                }
                // ---------- //

                // Crear un nuevo lector usando el mismo contenido de la cadena
                errMsg = "Error creating a new reader using the same content";
                Reader specificReader = new StringReader(decryptedContent);

                SondaResult result = null;

                // Verificar el tipo de respuesta y procesarla
                log.info("Verificando el tipo de respuesta...");
                errMsg = "Error al procesar la respuesta según su tipo";
                if (decryptedContent.contains("type=\"ulr\"")) {
                    // Marshaller específico para Ulr
                    log.info("Getting Ulr Specific Marshaller");
                    responseUnmarshaller = getResponseXmlUnmarshallerUlr();
                    ITransformer<SondaXmlUlrResponse, SondaResult> transformer = new SondaXmlUlrResponseTransformer();

                    // Transformador específico para Ulr
/*                    String respExecState = decryptedContent.split("<status value=\"")[1].split("\" description")[0];
                    if (!(respExecState.equalsIgnoreCase("OK") || respExecState.equalsIgnoreCase("ERROR"))) {
                        errMsg = execState_ERROR;
                        logErrAndException(execState_ERROR, execState_ERROR, null, true);
                    }*/
                    try {
                        result = transformer.transform((SondaXmlUlrResponse) responseUnmarshaller.unmarshal(specificReader));
                        try{
                            if(result.getImsi() != null && !"".equals(result.getImsi())){
                                MccMnc mccMncAuxUlr = operatorService.getMccMncFromImsi(result.getImsi());
                                result.setMcc(mccMncAuxUlr.getMcc());
                                result.setMnc(mccMncAuxUlr.getMnc());
                            }
                        }catch (Exception e) {
                            //logErrAndException(OPERATOR_FROM_MCCMNC_ERROR, OPERATOR_FROM_MCCMNC_ERROR, e, true);
                        }
                    } catch (Exception e) {
                        errMsg = UNMARSHALLER_ERROR;
                        logErrAndException(UNMARSHALLER_ERROR, UNMARSHALLER_ERROR, e, true);
                    }

                }
                else if (decryptedContent.contains("type=\"idr\"")) {
                    // Marshaller específico para Idr
                    log.info("Getting Idr Specific Marshaller");
                    responseUnmarshaller = getResponseXmlUnmarshallerIdr();
                    ITransformer<SondaXmlIdrResponse, SondaResult> transformer = new SondaXmlIdrResponseTransformer();

                    // Transformador específico para Idr
                    try {
                        boolean respNodoPromote = decryptedContent.contains("promote");
                        if (!respNodoPromote) {
                            String respExecState = "";
                            try{
                                respExecState = decryptedContent.split("<status value=\"")[1].split("\" description")[0];
                            }catch (Exception e){
                                log.error("Etiqueta <status> no encontrada en el archivo, no se puede procesar el fichero correctamente");
                            }
                            if (!(respExecState.equalsIgnoreCase("OK") || respExecState.equalsIgnoreCase("ERROR")|| respExecState.equalsIgnoreCase("KO"))) {
                                errMsg = execState_ERROR;
                                logErrAndException(execState_ERROR, execState_ERROR, null, false);
                            } //TODO: Revisar
                        }
                        result = transformer.transform((SondaXmlIdrResponse) responseUnmarshaller.unmarshal(specificReader));
                        if(result.getDetail().contains(PROCESSING_ERROR)){
                            log.error(result.getDetail());
                            if(trIdAux == null){
                                trIdAux = extractTrid(file.getName());
                            }
                        }
                    } catch (Exception e) {
                        errMsg = UNMARSHALLER_ERROR;
                        logErrAndException(UNMARSHALLER_ERROR, UNMARSHALLER_ERROR, e, true);
                    }

                    // Buscar el operador a través de Mcc y Mnc
                    errMsg = "Error getting operator from MccMnc";
                    List<Operator> operadora = new ArrayList<>();
                    try {
                        log.info("Getting operator from MccMnc");
                        operadora = operatorService.getOperatorByMcc_Mnc(result.getMcc(), result.getMnc());
                    } catch (Exception e) {
                        errMsg = OPERATOR_FROM_MCCMNC_ERROR;
                        logErrAndException(OPERATOR_FROM_MCCMNC_ERROR, OPERATOR_FROM_MCCMNC_ERROR, e, true);
                    }

                    if (!operadora.isEmpty()) {
                        result.setOperadora(operadora.get(0).getOperatorsName());
                    }
                }
                else {
                    // Marshaller específico para otros tipos de respuesta
                    log.info("Getting Specific Marshaller");
                    responseUnmarshaller = getResponseXmlUnmarshaller();
                    ITransformer<SondaXmlResponse, SondaResult> transformer = XmlResponseTransformerFactory.getTransformer(baseResponse.getType());

                    // Transformador específico para otros tipos de respuesta
                    //String respExecState = decryptedContent.split("<execState>")[1].split("</execState>")[0];

/*                    if (!(respExecState.equalsIgnoreCase("OK") || respExecState.equalsIgnoreCase("ERROR"))) {
                        errMsg = execState_ERROR;
                        logErrAndException(execState_ERROR, execState_ERROR, null, true);
                    }*/
                    try {
                        result = transformer.transform((SondaXmlResponse) responseUnmarshaller.unmarshal(specificReader));
                    } catch (Exception e) {
                        errMsg = UNMARSHALLER_ERROR;
                        logErrAndException(UNMARSHALLER_ERROR, UNMARSHALLER_ERROR, e, true);
                    }
                }

                // Buscando trId en BBDD
                errMsg = "Error al buscar trId en la base de datos";
                log.debug("{} findOneByID [{}]", transactionId, result.getRequestId().getId());
                SondaRequest sondaRequest = null;
                try {
                    log.info("Buscando Request en BD para trId: {}", trIdAux);
                    if(result.getDetail().contains(PROCESSING_ERROR)){
                        log.error(result.getDetail());
                        if(trIdAux == null || "".equals(trIdAux)){
                            trIdAux = extractTrid(file.getName());
                        }
                    }else{
                        if(trIdAux == null || "".equals(trIdAux)){
                            trIdAux = extractTrid(file.getName());
                            result.setDetail(PROCESSING_ERROR + GET_TRID_ERROR);
                        }
                    }
                    sondaRequest = sondaRequestRepository.findOneById(result.getRequestId().getId());
                    if (sondaRequest == null) {
                        sondaRequest = sondaRequestRepository.findOneById(trIdAux);
                    }
                } catch (Exception e) {
                    errMsg = TRID_DB_ERROR;
                    logErrAndException(TRID_DB_ERROR, TRID_DB_ERROR, e, true);
                }

                if (sondaRequest == null) {
                    log.info("{} Request not found for result [{}]", transactionId, result.getRequestId().getId());
                    errMsg = TRID_DB_ERROR;
                    logErrAndException(TRID_DB_ERROR, TRID_DB_ERROR, null, true);
                } else {

                    // Check for repeated results
                    errMsg = "Se han encontrado resultados repetidos";
                    checkRepeatedResults(sondaRequest, result);

                    log.debug("{} triggerName [{}]", transactionId, sondaRequest.getTriggerName());

                    MccMnc mccMnc = new MccMnc();
                    mccMnc.setMnc(result.getMnc());
                    mccMnc.setMcc(result.getMcc());

                    // Check rules
                    errMsg = "Error al verificar las reglas";
                    try {
                        procesarReglasService.checkReglas(result, sondaRequest);
                    } catch (Exception e) {
                        log.error("Ocurrió un error al verificar las reglas: {}", e.getCause().toString());
                    }

                    boolean isReintento = false;

                    // Technology
                    errMsg = "Error al obtener la tecnología";
                    String tecnologia = null;
                    if (!(STATE_1.equals(result.getState()))) {
                        tecnologia = getTecnologia(result, sondaRequest, file, targetPath, mccMnc);
                    }

                    log.info("Processing file, type:{}", result.getType());
                    errMsg = "Error processing response";

                    //SRIFSM
                    if (SRIFSM.equals(result.getType())) {
                        errMsg = "Error processing SRIFSM";
                        log.info("Processing SRIFSM result");

                        if (STATE_0.equals(result.getState())) {
                            log.info("Processing SRIFSM result with state 0");
                            procesarPrefijos(result);

                            if (!(sondaRequest.getType().equals(SondaRequestEnum.SRIFSM_ONCE.getIdentifier()))) {
                                log.info("Checking if PSIRes and IDR are OK or if line is in Roaming");

                                boolean psiOk = false;
                                boolean existPsi = false;
                                boolean idrOk = false;
                                boolean existIdr = false;

                                // Check PSI and IDR results
                                for (SondaResult sondaResult : sondaRequest.getResults()) {
                                    if (sondaResult.getType().equals("PSI")) {
                                        existPsi = true;
                                        if (sondaResult.getState().equals("0")) {
                                            psiOk = true;
                                        } else {
                                            sondaRequest.setState(1);
                                        }
                                    }
                                    if (sondaResult.getType().equals("IDR")) {
                                        existIdr = true;
                                        if (sondaResult.getState().equals("0")) {
                                            idrOk = true;
                                        } else {
                                            sondaRequest.setState(1);
                                        }
                                    }
                                }

                                String ccGtMsc = getInternationalCode(result.getMscgt());
                                String ccGtMsisdn = getInternationalCode(result.getMsisdn());

                                if (((!psiOk || !existPsi) || (!idrOk || !existIdr)) && (ccGtMsc != null && ccGtMsisdn != null && !ccGtMsc.equals(ccGtMsisdn))) {
                                    log.info("Line is in Roaming, updating country");
                                    Country country = null;

                                    try {
                                        country = getCountry(result.getMscgt());
                                    } catch (Exception e) {
                                        log.error("País no encontrado para GT: {}", result.getVlrgt());
                                    }

                                    sondaRequest.setCountry(country);
                                }
                            }

                        } else if (STATE_1.equals(result.getState())) {
                            log.info("Processing SRIFSM result with state 1");
                            sondaRequest.setState(-1);
                        }
                    }
                    //PSI o ATI
                    else if (PSI.equals(result.getType()) || ATI.equals(result.getType())) {
                        errMsg = "Error processing PSI or ATI";
                        log.info("Processing PSI or ATI result");

                        if (STATE_0.equals(result.getState())) {
                            log.info("Processing result with state 0");

                            result = getCellLocation(result);
                            log.debug("{} Operation [{}] -> OK", transactionId, result.getType());
                            sondaRequest.setState(2);

                            // Add rel IMSI_NODO
                            try {
                                String imsi = result.getImsi();
                                String mscGt = result.getMscgt();
                                MccMnc mccMncAux = operatorService.getMccMncFromImsi(imsi);
                                if (mscGt.equals("") && result.getMsc_list() != null) {
                                    mscGt = result.getMsc_list().get(result.getMsc_list().size() - 1).getGt();
                                }
                                if (imsi != null && mscGt != null) {
                                    ImsiNodo imsiNodo = new ImsiNodo(imsi, mscGt, null, mccMncAux.getMcc(), mccMncAux.getMnc(), "MSC", LocalDateTime.now());
                                    imsiNodoService.insertImsiNodo(imsiNodo);
                                }
                            } catch (Exception e) {
                                log.info("Unable to update IMSI / NODO relation");
                            }

                            // Programada
                            programadaRequest(transactionId, sondaRequest);

                            // Set country for PSIReq result
                            Country country = null;
                            try {
                                country = getCountry(result.getVlrgt());
                            } catch (Exception e) {
                                log.error("País no encontrado para GT: {}", result.getVlrgt());
                            }

                            sondaRequest.setCountry(country);
                            if (country != null) {
                                log.debug("{} Setting country '{}' with prefix '{}' to response of request '{}'", transactionId, country.getName(), country.getInternationalCode(), sondaRequest.getId());
                            }

                        } else if (sondaRequest.getState() != -4 && STATE_1.equals(result.getState()) && sondaRequest.getTriggerName() != null) {
                            log.info("Processing result with state 1");

                            Boolean existsSRI = sondaResultRepository.existsByRequestId(sondaRequest);

                            // Programada
                            if (sondaRequest.getTriggerName() != null && !existsSRI) {
                                // Delete PSIReq/IDRReq
                                sondaRequestRepository.deleteById(sondaRequest.getId());
                                // Retry SRIReq
                                writeSriRequest(sondaRequest, result);
                            }

                            sondaRequest.setState(-4);

                            // Retry with another technology if possible
                            if (!sondaRequest.getResults().isEmpty() && sondaRequest.getTriggerName() != null) {
                                SondaResult resultAux = sondaRequest.getResults().get(0);
                                if (restrictionsService.isRestricted(resultAux.getMnc(), sondaRequest.getResults().get(0).getMcc(), DIAMETER)) {
                                    sondaRequest.setState(-2);
                                } else {
                                    log.info("Retrying with Diameter");
                                    sondaRequest.setType(SondaRequestEnum.IDR.getIdentifier());
                                    writeIdaRequest(result.getRequestId().getId(), resultAux, transactionId);
                                }
                            } else {
                                sondaRequest.setState(-2);
                            }

                        } else if (sondaRequest.getFallback() && PSI.equals(result.getType())) {
                            log.info("Fallback condition for PSI");

                            sondaRequest.setFallback(false);
                            sondaRequest.setState(-2);
                            MccMnc mccMncAux = operatorService.getMccMncFromImsi(sondaRequest.getNumber());
                            if (restrictionsService.isRestricted(mccMncAux.getMnc(), mccMncAux.getMcc(), "DIAMETER")) {
                                sondaRequest.setState(-2);
                            } else {
                                isReintento = true;
                                log.info("[FALLBACK] Retrying with DIAMETER");
                                retraying = true;
                                RequestFileWriterService requestFileWriterService = new RequestFileWriterService(appConfigService, sondaRequestRepository, mmeService, mscService, hssService, imsiNodoService, sondaResultJPARepository, operatorService, restrictionsService, sondaRequestJPARepository, reqNodoService);
                                try {

                                    Boolean priorityInConfig = "0".equals(appConfigService.getConfigurationMap().get("allowIgnoreBlackOrWhiteList").getValor());
                                    requestFileWriterService.writeIDRReqFallback(transactionId, sondaRequest.getNumber(), sondaRequest.getName(), sondaRequest.getPriority(), priorityInConfig, "0", 0, sondaRequest.getUser(), sondaRequest.getMotivo(), sondaRequest.getComentarios());

                                    // Update bulk query
                                    if (masivasService.getBulkQueryByReqId(sondaRequest.getId()) != null) {
                                        BulkQuery query = masivasService.getBulkQueryByReqId(sondaRequest.getId());
                                        query.setLastupdated(LocalDateTime.now());
                                        query.setType("IDR");
                                        query.setReqTransactionId(transactionId);
                                        query.setEstado(1);
                                        masivasService.insertOrUpdateProgramming(query);
                                    }
                                } catch (IOException | JAXBException | CryptoException e) {
                                    log.error("Error while writing the PSI fallback request to imsi {}", result.getImsi(), e.getCause());
                                    isReintento = false;
                                    e.printStackTrace();
                                }
                            }

                        } else {
                            log.info("Result with unknown state, setting sondaRequest state to -2");
                            sondaRequest.setState(-2);
                        }
                    }
                    //IDR
                    else if (IDR.equals(result.getType())) {
                        errMsg = "Error processing IDR";
                        log.info("Processing IDR result");

                        if (sondaRequest.getState() != -4 && "1".equals(result.getState()) && sondaRequest.getTriggerName() != null) {
                            log.info("State is 1 and triggerName is not null");

                            Boolean existsSRI = sondaResultRepository.existsByRequestId(sondaRequest);
                            Boolean isImsi = programmingService.getProgrammingById(sondaRequest.getTriggerName()).getType().equals("IMSI");

                            if (sondaRequest.getTriggerName() != null && !existsSRI && isImsi) {
                                log.info("Retrying SRIReq due to IMSI");

                                // Delete PSIReq / IDRReq
                                sondaRequestRepository.deleteById(sondaRequest.getId());

                                // Retry SRIReq
                                writeSriRequest(sondaRequest, result);
                            } else if ("1".equals(result.getState()) && !isImsi) {
                                sondaRequest.setState(-4);

                                if (!sondaRequest.getResults().isEmpty()) {
                                    log.info("Attempting retry with SS7");

                                    SondaResult resultAux = sondaRequest.getResults().get(0);
                                    // Retry with another technology if possible
                                    if (restrictionsService.isRestricted(resultAux.getMnc(), sondaRequest.getResults().get(0).getMcc(), "SS7")) {
                                        sondaRequest.setState(-2);
                                    } else {
                                        log.info("Retrying with SS7");
                                        sondaRequest.setType(SondaRequestEnum.PSI.getIdentifier());
                                        writePsiRequest(result.getRequestId().getId(), resultAux, transactionId);
                                    }
                                } else {
                                    sondaRequest.setState(-2);
                                }
                            } else {
                                sondaRequest.setState(-2);
                            }
                        } else if ("0".equals(result.getState())) {
                            log.info("State is 0, processing IDR result as OK");

                            result = getCellLocation(result);
                            log.debug("{} Operation [{}] -> OK", transactionId, result.getType());
                            sondaRequest.setState(2);

                            // Scheduled request
                            programadaRequest(transactionId, sondaRequest);

                            Country country = null;
                            try {
                                country = getOperator(mccMnc).getCountry();
                            } catch (Exception e) {
                                log.error("País no encontrado para MCC {}", result.getMcc());
                            }
                            sondaRequest.setCountry(country);

                            if (country != null) {
                                log.debug("{} Setting country '{}' with prefix '{}' to response of request '{}'", transactionId, country.getName(), country.getInternationalCode(), sondaRequest.getId());
                            }

                            // Add IMSI_NODO relation
                            try {
                                String imsi = result.getImsi();
                                String mmeHost = result.getMmeHost();
                                String mmeRealm = result.getMmeRealm();
                                MccMnc mccMncAux = operatorService.getMccMncFromImsi(imsi);

                                if (imsi != null && mmeHost != null) {
                                    ImsiNodo imsiNodo = new ImsiNodo(imsi, mmeHost, mmeRealm, mccMncAux.getMcc(), mccMncAux.getMnc(), "MME", LocalDateTime.now());
                                    imsiNodoService.insertImsiNodo(imsiNodo);
                                }
                            } catch (Exception e) {
                                log.info("Failed to update IMSI / NODO relation");
                            }

                            ServiceParameters parameters = new ServiceParameters();
                            parameters.getParamsMap().put("hostname", result.getMmeHost());
                            parameters.getParamsMap().put("realm", result.getMmeRealm());
                            parameters.getParamsMap().put("priority", 1);
                            parameters.getParamsMap().put(ControllerConstants.MCC_MNC, mccMnc);
                        } else if (sondaRequest.getFallback()) {
                            log.info("Fallback condition met");
                            boolean isSri = false;
                            if(sondaRequest.getType().equals(SondaRequestEnum.SRIFSM.getIdentifier())){
                                if(!sondaResultRepository.findByRequestId(sondaRequest).isEmpty()){
                                    switch (sondaResultRepository.findByRequestId(sondaRequest).get(0).getType()) {
                                        case SRIFSM:
                                            isSri = true;
                                            break;
                                    }
                                }
                            }
                            String number = "";
                            sondaRequest.setFallback(false);
                            if(isSri){
                                number = sondaResultRepository.findByRequestId(sondaRequest).get(0).getImsi();
                            }else{
                                sondaRequest.setState(-2);
                                number = sondaRequest.getNumber();
                            }

                            MccMnc mccMncAux = operatorService.getMccMncFromImsi(number);

                            if (restrictionsService.isRestricted(mccMncAux.getMnc(), mccMncAux.getMcc(), "SS7")) {
                                sondaRequest.setState(-2);
                            } else {
                                isReintento = true;
                                log.info("[FALLBACK] Retrying with SS7");
                                retraying = true;
                                RequestFileWriterService requestFileWriterService = new RequestFileWriterService(appConfigService, sondaRequestRepository, mmeService, mscService, hssService, imsiNodoService, sondaResultJPARepository, operatorService, restrictionsService, sondaRequestJPARepository, reqNodoService);

                                try {
                                    Boolean priorityInConfig = "0".equals(appConfigService.getConfigurationMap().get("allowIgnoreBlackOrWhiteList").getValor());
                                    requestFileWriterService.writePSIReqFallback(transactionId, number, sondaRequest.getName(), sondaRequest.getPriority(), priorityInConfig, "0", 0, sondaRequest.getUser(), sondaRequest.getMotivo(), sondaRequest.getComentarios());

                                    // Update bulk query
                                    if (masivasService.getBulkQueryByReqId(sondaRequest.getId()) != null) {
                                        BulkQuery query = masivasService.getBulkQueryByReqId(sondaRequest.getId());
                                        query.setLastupdated(LocalDateTime.now());
                                        query.setType("PSI");
                                        query.setReqTransactionId(transactionId);
                                        query.setEstado(1);
                                        masivasService.insertOrUpdateProgramming(query);
                                    }
                                } catch (IOException | JAXBException | CryptoException e) {
                                    log.error("Error while writing the PSI fallback request to IMSI {}", result.getImsi(), e.getCause());
                                    isReintento = false;
                                    e.printStackTrace();
                                }
                            }
                        } else {
                            sondaRequest.setState(-2);
                        }
                    }
                    //ULR
                    else if (ULR.equals(result.getType())) {
                        result.setFile(file.getName());
                        // Save to Database
                        try {
                            log.debug("{} Saving to data base", transactionId);
                            sondaResultJPARepository.saveAndFlush(result);
                        } catch (JpaSystemException je) {
                            je.printStackTrace();
                        } catch (Exception e) {
                            log.error(e.getMessage());
                        }
                    } else {
                        sondaRequest.setState(-3);
                    }


                    if (!(("PSI".equals(result.getType()) ||
                            "ATI".equals(result.getType())) &&
                            "1".equals(result.getState())
                            && sondaRequest.getTriggerName() != null
                            && !sondaResultRepository.existsByRequestId(sondaRequest))) {

                        processAndSaveRequestResult(sondaRequest,result, sondaRequestJPARepository,
                                sondaResultJPARepository, file, isReintento, tecnologia,
                                restrictionsService, transactionId, retraying);
                    }

                }

                if(result.getDetail().contains(PROCESSING_ERROR)){
                    errMsg = result.getDetail();
                    throw new Exception(result.getDetail());
                }

                // Move the actual file to target directory
                Files.move(file.toPath(), targetPath.resolve(file.getName() + dateCodeName), StandardCopyOption.REPLACE_EXISTING);

                // Llamar a un comando definido
                doCommand(file, targetPath, dateCodeName);
            } else {
                errMsg = GET_RESPONSE_TYPE_ERROR;
                logErrAndException(GET_RESPONSE_TYPE_ERROR, GET_RESPONSE_TYPE_ERROR_2, null, true);
            }

        } catch (Exception e) {
            log.info("Error in step: " + errMsg);
            logErrAndException(errMsg, errMsg, e, true);
        }


    }

    private void processAndSaveRequestResult(SondaRequest sondaRequest, SondaResult result,
                                             ISondaRequestJPARepository sondaRequestJPARepository,
                                             SondaResultJPARepository sondaResultJPARepository,
                                             File file, boolean isReintento, String tecnologia,
                                             RestrictionsService restrictionsService,
                                             String transactionId, boolean retraying) {
        result.setFile(file.getName());

        Integer priority = (sondaRequest.getPriority() != null && sondaRequest.getPriority()) ? 1 : 0;

        String errMsg = "";
        // Guardar en la base de datos
        try {
            errMsg = "Guardando en la base de datos";
            log.debug("{} {}", transactionId, errMsg);

            sondaRequestJPARepository.saveAndFlush(sondaRequest);
            if (!sondaRequest.getState().equals(-4) && !isReintento) {
                sondaResultJPARepository.saveAndFlush(result);
                csvBulkQuery(sondaRequest, result);
            }else if(!sondaRequest.getState().equals(-4) && retraying){
                sondaResultJPARepository.saveAndFlush(result);
            }

            if (SRIFSM.equals(result.getType()) && "0".equals(result.getState()) && !"1".equals(sondaRequest.getType())) {
                if (tecnologia == null) {
                    log.info(PAIR_DISALLOWED, transactionId, result.getMcc(), result.getMnc());
                    SondaResult result1 = new SondaResult();
                    result1.setRequestId(sondaRequest);
                    result1.setState("1");
                    result1.setDetail(RESTRICTION_MSG);
                    String type = "PSI";

                    result1.setType(type);
                    result1.setFile("none");
                    result1.setDate(LocalDateTime.now());
                    sondaResultJPARepository.saveAndFlush(result1);
                } else {
                    Map<String, Configuration> config = appConfigService.getConfigurationMap();
                    Configuration fallbackConfig = config.get("fallbackQuery");
                    String isFallback = fallbackConfig != null ? fallbackConfig.getValor() : "0";
                    sondaRequest.setFallback("1".equals(isFallback));
                    sondaRequestJPARepository.saveAndFlush(sondaRequest);
                    if (tecnologia.equals("SS7")) {

                        //if the srifsm is ok, send a new PSI petition
                        if (sondaRequest.getType().equals(SondaRequestEnum.SRIFSM_ONCE.getIdentifier())) {
                            log.info("{} Response is SRIFSM_ONCE (search imsi by msisdn). PSI request do not send.", transactionId);
                        } else if (!restrictionsService.isRestricted(result.getMnc(), result.getMcc(), tecnologia)) {
                            writeRequest(sondaRequest.getId(), result, transactionId);
                        } else if (priority == 1) {
                            writeRequest(sondaRequest.getId(), result, transactionId);
                        } else {
                            log.info(PAIR_DISALLOWED, transactionId, result.getMcc(), result.getMnc());
                            SondaResult result1 = new SondaResult();
                            result1.setRequestId(sondaRequest);
                            result1.setState("1");
                            result1.setDetail(RESTRICTION_MSG);
                            result1.setType("PSI");
                            result1.setFile("none");
                            result1.setDate(LocalDateTime.now());
                            sondaResultJPARepository.saveAndFlush(result1);
                        }


                    } else if (tecnologia.equals(DIAMETER)) {

                        //if the srifsm is ok, send a new IDR petition
                        if (sondaRequest.getType().equals(SondaRequestEnum.SRIFSM_ONCE.getIdentifier())) {
                            log.info("{} Response is SRIFSM_ONCE (search imsi by msisdn). IDR request do not send.", transactionId);
                        } else if (!restrictionsService.isRestricted(result.getMnc(), result.getMcc(), tecnologia)) {
                            writeIdaRequest(sondaRequest.getId(), result, transactionId);
                        } else if (priority == 1) {
                            writeIdaRequest(sondaRequest.getId(), result, transactionId);
                        } else {
                            log.info(PAIR_DISALLOWED, transactionId, result.getMcc(), result.getMnc());
                            SondaResult result1 = new SondaResult();
                            result1.setRequestId(sondaRequest);
                            result1.setState("1");
                            result1.setDetail(RESTRICTION_MSG);
                            result1.setType("IDR");
                            result1.setFile("none");
                            result1.setDate(LocalDateTime.now());
                            sondaResultJPARepository.saveAndFlush(result1);
                        }

                    } else {
                        log.info(PAIR_DISALLOWED, transactionId, result.getMcc(), result.getMnc());
                        SondaResult result1 = new SondaResult();
                        result1.setRequestId(sondaRequest);
                        result1.setState("1");
                        result1.setDetail(RESTRICTION_MSG);
                        result1.setType(result.getType());
                        result1.setFile("none");
                        result1.setDate(LocalDateTime.now());
                        sondaResultJPARepository.saveAndFlush(result1);
                    }
                }
            }

            //Guardar MSISDN/IMSI en la tabla IMSI (SRIFSM_ONCE)
            if ("0".equals(result.getState()) && ("1".equals(sondaRequest.getType()) || "S".equals(sondaRequest.getType()))) {
                log.info("Guardando MSISDN/IMSI en la tabla IMSI (SRIFSM_ONCE)");
                String msisdn = sondaRequest.getNumber();
                String imsi = result.getImsi();
                relMSISDN_IMSIService.insert(msisdn, imsi);
            }


            //Guardar MSISDN/IMSI en la tabla IMSI (Consulta MSISDN)
            if ("0".equals(result.getState()) && "U".equals(sondaRequest.getType())) {
                log.info("Guardar MSISDN/IMSI en la tabla IMSI (Consulta MSISDN)");
                String msisdn = result.getMsisdn();
                String imsi = sondaRequest.getNumber();
                relMSISDN_IMSIService.insert(msisdn, imsi);
            }


        } catch (JpaSystemException je) {
            errMsg = "Error al guardar en la base de datos (JpaSystemException)";
            log.error(errMsg);
            je.printStackTrace();
        } catch (Exception e) {
            errMsg = "Error al guardar en la base de datos";
            log.error(errMsg);
            log.error(e.getMessage());
        }
    }

/*    public void processAndSaveRequestResult(sondaRequest,result, sondaRequestJPARepository,
                               sondaResultJPARepository, file, isReintento, tecnologia,
                               restrictionsService,  relMSISDN_IMSIService, transactionId) */

    public static String extractTrIdFromFile(String input) {
        Pattern pattern = Pattern.compile("<trId>(.+?)</trId>");
        Matcher matcher = pattern.matcher(input);

        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return null;
        }
    }

    public static String extractTrid(String fileName) {
        String[] splitByUnderscore = fileName.split("_");

        if (splitByUnderscore.length == 1) {
            // Caso [trid].res
            return splitByUnderscore[0].split("\\.")[0];
        } else if (splitByUnderscore.length == 2) {
            // Caso [type]_[trid].res o [trid]_enc.res
            if (splitByUnderscore[1].contains("enc")) {
                return splitByUnderscore[0].split("\\.")[0];
            } else {
                return splitByUnderscore[1].split("\\.")[0];
            }
        } else if (splitByUnderscore.length == 3) {
            // Caso [type]_[trid]_enc.res
            return splitByUnderscore[1];
        } else {
            log.info("Nombre de archivo no válido: " + fileName);
            return null;
        }
    }

    public static String replaceTrId(String input, String newValue) {
        Pattern pattern = Pattern.compile("<trId>(.+?)</trId>");
        Matcher matcher = pattern.matcher(input);

        if (matcher.find()) {
            return matcher.replaceAll("<trId>" + newValue + "</trId>");
        } else {
            Pattern responsePattern = Pattern.compile("</response>");
            Matcher responseMatcher = responsePattern.matcher(input);

            if (responseMatcher.find()) {
                return responseMatcher.replaceAll("<trId>" + newValue + "</trId></response>");
            } else {
                return input;
            }
        }
    }

    private void csvBulkQuery(SondaRequest sondaRequest, SondaResult result) {
        if (PSI.equals(result.getType()) || IDR.equals(result.getType())) {
            //Comprobar si es consulta masiva
            if (masivasService.getBulkQueryByReqId(sondaRequest.getId()) != null) {
                BulkQuery query = masivasService.getBulkQueryByReqId(sondaRequest.getId());
                query.setLastupdated(LocalDateTime.now());
                query.setEstado(result.getState().equals("1") ? 2 : 3);
                masivasService.insertOrUpdateProgramming(query);
                if (masivasService.isBulkQuerySinRespuesta(query.getTransactionId()) && !masivasReqService.isCsvRes(query.getTransactionId())) {//No queda ninguna sin respuesta y no hay CSV de respuesta

                    try {
                        BulkQueryReq req = masivasReqService.getBulkQueryReq(query.getTransactionId());
                        try {
                            writeBulkCsvResponse(query, req);
                        } catch (Exception e) {
                            log.error("Se ha produdico un error al generar el CSV de respuesta para la consulta masiva: [{}]", query.getTransactionId());
                        }
                        req.setEstado(1);
                        req.setCsvRes(1);
                        masivasReqService.insertOrUpdateBulkQueryReq(req);
                    } catch (Exception ignored) {
                    }

                }
            }
        }
    }

    private void writeBulkCsvResponse(BulkQuery query, BulkQueryReq req) {
        List<BulkQuery> lBulkQuery = masivasService.findAllByQueryName(query.getQueryName());
        List<String[]> list = new ArrayList<>();
        String[] header = {"IMSI", "Tipo", "LastUpdate", "Estado Masiva", "Detalle Masiva", "Estado Response", "Detalle Response", "trId", "Operadora", "MCC", "MNC", "LAC", "Cell Id", "Longitud", "Latitud", "MME_HOST", "MME_REALM", "GT"};
        list.add(header);
        lBulkQuery.forEach(bulkQuery -> {
            SondaRequest sondaRequest = null;
            SondaResult result = null;
            if (bulkQuery.getReqTransactionId() != null) {
                // Get request
                sondaRequest = sondaRequestRepository.findOneById(bulkQuery.getReqTransactionId());
                // Get result
                if (sondaRequest != null) {
                    result = sondaResultRepository.findByRequestId(sondaRequest).get(0);
                }
            }

            String imsi = bulkQuery.getNumber();
            String tipo = bulkQuery.getType() == null ? "" : bulkQuery.getType();
            String lastUpdate = bulkQuery.getLastupdated().toString();
            String estadoMasiva = (bulkQuery.getEstado() == 1) ? "Sin respuesta" : ((bulkQuery.getEstado() == 2) ? "ERROR" : ((bulkQuery.getEstado() == 3) ? "OK" : "Consulta no lanzada"));
            String detalleMasiva = bulkQuery.getDetail() == null ? "" : bulkQuery.getDetail();
            String estadoRes = "";
            String detalleRes = "";
            String trId = "";
            String operadora = "";
            String mcc = "";
            String mnc = "";
            String lac = "";
            String cellId = "";
            String longitud = "";
            String latitud = "";
            String mmeHost = "";
            String mmeRealm = "";
            String gt = "";
            if (bulkQuery.getEstado() != 1 && bulkQuery.getEstado() != 0) {
                if (bulkQuery.getEstado() == 2) {
                    if (result != null) {
                        estadoRes = result.getState().equals("0") ? "ERROR" : "OK";
                        detalleRes = result.getDetail();
                        if ("".equals(detalleRes)) {
                            detalleRes = "";
                        }
                        if (sondaRequest != null) {
                            trId = sondaRequest.getId();
                            if (trId == null) trId = "";
                        }

                    }
                } else {
                    if (result != null) {
                        estadoRes = result.getState().equals("1") ? "ERROR" : "OK";
                        detalleRes = result.getDetail();
                    }
                    if (detalleRes == null) detalleRes = "";
                    if (sondaRequest != null) {
                        trId = sondaRequest.getId();
                    }
                    if (result != null) {
                        if (trId == null) trId = "";
                        operadora = result.getOperadora();
                        if (operadora == null) operadora = "";
                        mcc = result.getMcc();
                        if (mcc == null) mcc = "";
                        mnc = result.getMnc();
                        if (mnc == null) mnc = "";
                        lac = String.valueOf(result.getLac());
                        if (lac == null) lac = "";
                        cellId = result.getCellid();
                        if (cellId == null) cellId = "";
                        longitud = String.valueOf(result.getLongitud());
                        if (longitud == null) longitud = "";
                        latitud = String.valueOf(result.getLatitud());
                        if (latitud == null) latitud = "";
                        mmeHost = result.getMmeHost();
                        if (mmeHost == null) mmeHost = "";
                        mmeRealm = result.getMmeRealm();
                        if (mmeRealm == null) mmeRealm = "";
                        gt = result.getMscgt();
                        if (gt == null) {
                            gt = result.getVlrgt();
                            if (gt == null) {
                                gt = "";
                            }
                        }
                    }

                }
            }


            String[] record = {imsi, tipo, lastUpdate, estadoMasiva, detalleMasiva, estadoRes, detalleRes, trId, operadora, mcc, mnc, lac, cellId, longitud, latitud, mmeHost, mmeRealm, gt};
            list.add(record);
        });

        CsvWriterBulkQuery writer = new CsvWriterBulkQuery();
        try {
            String resPath = appConfigService.getConfigurationMap().get("bulkQueryResPath").getValor();
            checkAndCreatePath(Paths.get(resPath));
            writer.writeToCsvFile(list, new File(resPath + "/consulta_masiva_" + System.currentTimeMillis() + ".csv"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Cheacks and creates a directory if needed
     *
     * @param targetPath Directory Path
     */
    private void checkAndCreatePath(Path targetPath) throws IOException {
        if (!targetPath.toFile().exists()) {
            log.info("Creating Directory '{}'", targetPath.toAbsolutePath());
            Files.createDirectories(targetPath);
        }
    }

    /**
     * Llamar a un comando definido en la configuración con un argumento que sea el path absoluto del fichero xml resultado
     *
     * @param path
     */
    private void doCommand(File file, Path path, String dateCodeName) {
        log.info("Llamando al comando definido en la configuración...");
        String comando = appConfigService.getConfigurationMap().get("respQueryCommand").getValor();
        if (comando != null && !"".equals(comando)) {
            try {
                // Log para indicar que se está buscando el comando en la base de datos
                log.info("Buscando comando en BBDD");
                // Obtener el path del script a partir de la variable comando
                String scriptPath = comando;
                // Obtener el path absoluto del archivo
                String filePath = path + "/" + file.getName();
                // Log para mostrar el path del script
                log.info("Path del script: {}", scriptPath);
                // Log para mostrar el path del archivo
                log.info("Path del fichero: {}", filePath);
                // Crear un array de cadenas con los argumentos del comando
                String[] cmdArray = {"/bin/bash", scriptPath, filePath};
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    log.info("No se puede realizar la operación en un sistema operativo Windows");
                } else {
                    // Ejecutar el comando con Runtime.getRuntime().exec(cmdArray)
                    Runtime.getRuntime().exec(cmdArray);
                }

            } catch (IOException ioe) {
                // Log de error si se produce una excepción al ejecutar el script
                log.error("Error al ejecutar el script: {}", comando);
                log.error("Se produjo un error al ejecutar el comando: {}", ioe.getMessage());
            }


        }
    }

    private void checkRepeatedResults(SondaRequest sondaRequest, SondaResult result) throws JAXBException {
        List<SondaResult> results = sondaRequest.getResults();
        AtomicBoolean respuestaDuplicada = new AtomicBoolean(false);
        log.info("Comprobando si existen respuestas duplicadas...");
        if (results.isEmpty()) {
            log.info("No existen respuestas duplicadas");
        } else {
            if (sondaRequest.getType().equals(SondaRequestEnum.SRIFSM.getIdentifier())) {
                results.forEach(data -> {
                    if (data.getType().equals(result.getType())) {
                        respuestaDuplicada.set(true);
                    }
                });
            } else {
                respuestaDuplicada.set(true);
            }
            if (respuestaDuplicada.get()) {
                logErrAndException(REPEATED_RESULTS_ERROR, REPEATED_RESULTS_ERROR, null, true);
            } else {
                log.info("No existen respuestas duplicadas");
            }
        }
    }

    private void logErrAndException(String msg_1, String msg_2, Exception cause,boolean throwExc) throws JAXBException {
        if(cause == null){
            log.error("{}", msg_1);
        }else{
            log.error("{}: {}", msg_1, cause.toString());
        }
        if(throwExc){
            if (cause == null) {
                throw new JAXBException(msg_2);
            } else {
                throw new JAXBException(msg_2, cause.getMessage());
            }
        }
    }


    private void programadaRequest(String transactionId, SondaRequest sondaRequest) {
        if (sondaRequest.getTriggerName() != null && sondaRequest.getAlerta() == 1) {
            Programming programmingExample = new Programming();
            programmingExample.setProgrammingId(sondaRequest.getTriggerName());
            Programming programming = programmingService.getProgrammingByExample(Example.of(programmingExample));

            if (programming != null && programming.getAlert()) {
                log.debug("{} programming.getAlert [{}]", transactionId, programming.getAlert());
                log.info("{} Sending alert email '{}' -> {}", transactionId, sondaRequest.getNumber(), sondaRequest.getUser().getEmail());
                sondaRequest.setPriority(programming.getPriority());
                if (mailUtil.sendAlertMail(sondaRequest)) {
                    programming.setAlert(false);
                    programmingService.insertOrUpdateProgramming(programming);
                    log.debug("{} Disabling alert for user [{}] and number [{}] (first response OK with location OK)",
                            transactionId, sondaRequest.getUser().getLoginUser(), sondaRequest.getNumber());
                }
            }
        }
    }

    private String getTecnologia(SondaResult result, SondaRequest request, File file, Path targetPath, MccMnc mccMnc) throws IOException {
        //---Operadora -->Se decide Tecnología---//
        String tecnologia = "";
        String canalDefault = "";
        String canalAux = "";
        if (("1".equals(result.getState()))) {
            return null;
        } else {


            try {
                String mcc = null;
                String mnc = null;

                try {
                    mcc = result.getMcc();
                    mnc = result.getMnc();

                } catch (Exception e) {
                    log.info("No hay datos de operadora para el par Mcc Mnc en BBDD");
                }
                if (!(mnc == null || mcc == null)) {
                    try {
                        Operator op = getOperator(mccMnc);
                        canalDefault = (op.getCanal_preferido() == 1) ? DIAMETER : "SS7";
                        canalAux = (op.getCanal_preferido() != 1) ? DIAMETER : "SS7";
                    } catch (Exception e) {
                        log.info(ResponseErrorEnum.MCC_MNC_NOT_FOUND.getErrorDesc());
                        tecnologia = DIAMETER;
                        return tecnologia;
                    }

                } else {
                    log.info(ResponseErrorEnum.MCC_MNC_NOT_FOUND.getErrorDesc());
                    result.setDetail(ResponseErrorEnum.MCC_MNC_NOT_FOUND.getErrorDesc());
                    result.setState("1");
                    result.setFile(file.getName());
                    sondaResultJPARepository.saveAndFlush(result);
                    // Move the actual file to target directory
                    //Files.move(file.toPath(), targetPath.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
                    return null;
                }
            } catch (Exception e) {
                log.error(ResponseErrorEnum.MCC_MNC_NOT_FOUND.getErrorDesc());
                result.setDetail(ResponseErrorEnum.MCC_MNC_NOT_FOUND.getErrorDesc());
                result.setState("1");
                result.setFile(file.getName());
                sondaResultJPARepository.saveAndFlush(result);
                // Move the actual file to target directory
                //Files.move(file.toPath(), targetPath.resolve(file.getName()), StandardCopyOption.REPLACE_EXISTING);
                return tecnologia;
            }
            if (!restrictionsService.isRestricted(result.getMnc(), result.getMcc(), canalDefault)) {
                tecnologia = canalDefault;
            } else if (!restrictionsService.isRestricted(result.getMnc(), result.getMcc(), canalAux)) {
                tecnologia = canalAux;
            } else if (Boolean.TRUE.equals(request.getPriority())) {
                tecnologia = canalDefault;
            } else {
                tecnologia = null;
            }
            if ((tecnologia != null) && tecnologia.equals("")) tecnologia = null;

            return tecnologia;
        }
    }

    /**
     * If a number belongs to a friendly&co, write a ATI request. Else, write a PSI request.
     *
     * @param requestId
     * @param result
     * @param transactionId
     */
    private void writeRequest(String requestId, SondaResult result, String transactionId) {
        //String operatorMcc = appConfigService.getConfigurationMap().get("trustOperatorMcc").getValor();
        //String operatorMnc = appConfigService.getConfigurationMap().get("trustOperatorMnc").getValor();

        //Trust Operator
        String resultOperators = result.getMcc() + result.getMnc();
        List<String> lTrustOperators = getTrustOperatorsList();


        if (lTrustOperators.isEmpty() || !lTrustOperators.contains(resultOperators)) {
            log.info("{} Writting PSI request.", transactionId);
            writePsiRequest(requestId, result, transactionId);
        } else if (lTrustOperators.contains(resultOperators)) {
            log.info("{} Writting ATI request.", transactionId);
            writeAtiRequest(requestId, result, transactionId);
        }
    }

    private void writePsiRequest(String requestId, SondaResult result, String transactionId) {
        log.info("Found SRIFSM OK. Sending a PSI request to imsi {}", result.getImsi());
        RequestFileWriterService requestFileWriterService = new RequestFileWriterService(appConfigService, sondaRequestRepository, mmeService, mscService, hssService, imsiNodoService, sondaResultJPARepository, operatorService, restrictionsService, sondaRequestJPARepository, reqNodoService);
        try {
            requestFileWriterService.writePsiRequest(transactionId, requestId, result, null, sondaRequestRepository);
        } catch (IOException | JAXBException | CryptoException e) {
            log.error("Error while writing the PSI request to imsi {}", result.getImsi(), e.getCause());
            e.printStackTrace();
        }
    }

    private void writeAtiRequest(String requestId, SondaResult result, String transactionId) {
        log.info("Found SRIFSM OK. Sending a ATI request to imsi {}", result.getImsi());
        RequestFileWriterService requestFileWriterService = new RequestFileWriterService(appConfigService, sondaRequestRepository, mmeService, mscService, hssService, imsiNodoService, sondaResultJPARepository, operatorService, restrictionsService, sondaRequestJPARepository, reqNodoService);
        try {
            requestFileWriterService.writeAtiRequest(transactionId, requestId, result);
        } catch (IOException | JAXBException | CryptoException e) {
            log.error("Error while writing the ATI request to imsi {}", result.getImsi(), e.getCause());
            e.printStackTrace();
        }
    }

    private void writeSriRequest(SondaRequest sondaRequest, SondaResult result) {
        RequestFileWriterService requestFileWriterService = new RequestFileWriterService(appConfigService, sondaRequestRepository, mmeService, mscService, hssService, imsiNodoService, sondaResultJPARepository, operatorService, restrictionsService, sondaRequestJPARepository, reqNodoService);
        try {
            String alert;
            String priority;
            if (sondaRequest.getAlerta() == 0) {
                alert = "0";
            } else {
                alert = "1";
            }
            if (Boolean.TRUE.equals(sondaRequest.getPriority())) {
                priority = "1";
            } else {
                priority = "0";
            }
            ServiceParameters serviceParameters = new ServiceParameters();
            serviceParameters.setTransactionId(sondaRequest.getId());
            serviceParameters.setUser(sondaRequest.getUser());
            serviceParameters.getParamsMap().put(ControllerConstants.IDENTIFIER, sondaRequest.getNumber());
            serviceParameters.getParamsMap().put(ControllerConstants.TYPE, SondaRequestEnum.SRIFSM);
            serviceParameters.getParamsMap().put(ControllerConstants.RANGOFECHAS, true);
            serviceParameters.getParamsMap().put(ControllerConstants.TRIGGER_NAME, sondaRequest.getTriggerName());
            serviceParameters.getParamsMap().put(ControllerConstants.NAME, sondaRequest.getName());
            serviceParameters.getParamsMap().put(ControllerConstants.ALERT, alert);
            serviceParameters.getParamsMap().put(ControllerConstants.PRIORITY, priority);
            Boolean existsSRI = sondaResultRepository.existsByRequestId(sondaRequest);
            serviceParameters.getParamsMap().put("existsSRI", existsSRI);

            //serviceParameters.getParamsMap().put(ControllerConstants.STATUS, sondaRequest.getState());

            requestFileWriterService.rewriteSRIFSMRequest(serviceParameters);
        } catch (IOException | JAXBException | CryptoException e) {
            log.error("Error while writing the SRI request ", e.getCause());
        }
    }

    private void writeIdaRequest(String requestId, SondaResult result, String transactionId) {
        log.info("Sending a IDR request to imsi {}", result.getImsi());
        RequestFileWriterService requestFileWriterService = new RequestFileWriterService(appConfigService, null, mmeService, mscService, hssService, imsiNodoService, sondaResultJPARepository, operatorService, restrictionsService, sondaRequestJPARepository, reqNodoService);
        try {
            requestFileWriterService.writeIdaRequest(transactionId, requestId, result);
        } catch (IOException | JAXBException | CryptoException e) {
            log.error("Error while writing the IDA request to imsi {}", result.getImsi(), e.getCause());
            e.printStackTrace();
        }
    }

    public SondaResult getCellLocation(SondaResult result) {
        log.debug("Obteniendo localización de la celda");
        Map<String, Configuration> configuration = appConfigService.getConfigurationMap();
        String entorno = configuration.get("entorno").getValor();
        String url = configuration.get("urlApiCellLocation").getValor();
        String url2 = configuration.get("urlApiCellLocation2").getValor();
        ApiCellLocation apiCellLocation = ApiCellLocationFactory.getApiCellLocationImplementation(entorno);
        CellLocationRequest request = new CellLocationRequest(result.getMcc(), result.getMnc(),
                result.getLac().toString(), result.getCellid());
        CellLocationResponseObj response = apiCellLocation.getCellLocation(request, url, url2);
        if (response != null) {
            log.debug("Obtenida respuesta de localizacion");
            result.setLongitud(Double.parseDouble(response.getDataResponse().getX().replace("_", "")));
            result.setLatitud(Double.parseDouble(response.getDataResponse().getY().replace("_", "")));
            if (response.getDataResponse().getRadio() != null && !"".equals(response.getDataResponse().getRadio())) {
                result.setRadius(Double.parseDouble(response.getDataResponse().getRadio().replace("_", "")));
            }
            if (response.getAllCoords() != null) {
                result.setAllCoordinates(response.getAllCoords());
            }
            result.setGeohash(GeoHash.geoHashStringWithCharacterPrecision(result.getLatitud(), result.getLongitud(), 12));
        }
        return result;
    }

    /**
     * This methods return the international code of a Global Title
     *
     * @param gt global title to get the international code
     * @return international code
     */
    public Country getCountry(String gt) {

        String plusGt = "+" + gt;
        PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

        Phonenumber.PhoneNumber phonenumber = null;
        try {
            phonenumber = phoneNumberUtil.parse(plusGt, "");
            log.debug("Obtenido del gt {} el prefijo", phonenumber.getCountryCode());
            if (null != phonenumber) {
                return countryCCService.findCountryByPrefix(String.valueOf(phonenumber.getCountryCode()));
            }
        } catch (NumberParseException e) {
            log.debug("Error al obtener del gt {} el prefijo internacional", gt);
            return null;
        }
        return null;
    }

    /**
     * This methods return the international code of a Global Title
     *
     * @param gt global title to get the international code
     * @return international code
     */
    public String getInternationalCode(String gt) {

        String plusGt = "+" + gt;
        PhoneNumberUtil phoneNumberUtil = PhoneNumberUtil.getInstance();

        Phonenumber.PhoneNumber phonenumber = null;
        try {
            phonenumber = phoneNumberUtil.parse(plusGt, "");
            log.debug("Obtenido del gt {} el prefijo", phonenumber.getCountryCode());
            if (null != phonenumber) {
                return String.valueOf(phonenumber.getCountryCode());
            }
        } catch (NumberParseException e) {
            log.debug("Error al obtener del gt {} el prefijo internacional", gt);
            return null;
        }
        return null;
    }

    /**
     * Gets an instance of the Unmarshaller
     *
     * @return instance of the Unmarshaller
     * @throws JAXBException unexpected
     */
    private Unmarshaller getGenericXmlUnmarshaller() throws JAXBException {
        if (genericUnmarshaller == null) {
            genericUnmarshaller = XmlUnmarshallerFactory.createUnmarshaller(SondaGenericXmlResponse.class, false);
        }
        return genericUnmarshaller;
    }

    /**
     * Gets an instance of the Unmarshaller
     *
     * @return instance of the Unmarshaller
     * @throws JAXBException unexpected
     */
    private Unmarshaller getResponseXmlUnmarshaller() throws JAXBException {

        responseUnmarshaller = XmlUnmarshallerFactory.createUnmarshaller(SondaXmlResponse.class, true);
        return responseUnmarshaller;
    }

    /**
     * Gets an instance of the Unmarshaller
     *
     * @return instance of the Unmarshaller
     * @throws JAXBException unexpected
     */
    private Unmarshaller getResponseXmlUnmarshallerUlr() throws JAXBException {
        responseUnmarshaller = XmlUnmarshallerFactory.createUnmarshaller(SondaXmlUlrResponse.class, true);
        return responseUnmarshaller;
    }

    /**
     * Gets an instance of the Unmarshaller
     *
     * @return instance of the Unmarshaller
     * @throws JAXBException unexpected
     */
    private Unmarshaller getResponseXmlUnmarshallerIdr() throws JAXBException {
        responseUnmarshaller = XmlUnmarshallerFactory.createUnmarshaller(SondaXmlIdrResponse.class, true);
        return responseUnmarshaller;
    }

    /**
     * Generates a String with the decrypted content of the given file
     *
     * @param encryptedFile Encrypted file
     * @return decrypted content of the given file
     * @throws CryptoException unexpected
     * @throws IOException     unexpected
     */
    private String decryptFile(File encryptedFile) throws CryptoException, IOException {
        log.debug("Decrypting file '{}'...", encryptedFile.getName());

        // Copies the file's content into a StringWriter
        StringWriter writer = new StringWriter();
        try (FileInputStream fis = new FileInputStream(encryptedFile)) {
            IOUtils.copy(fis, writer, StandardCharsets.UTF_8);
            writer.close();
        }

        // Decrypts the Content
        return AesCryptoManager.decryptText(appConfigService.getCryptoKey(), writer.toString());
    }

    private void procesarPrefijos(SondaResult sondaResult) throws JAXBException {
        try {
            log.info("Processing prefixes");
            // Query prefijos
            List<CountryCC> resultPage = countryCCService.getAllCountry();
            Map<String, String> mapPrefijos = new HashMap<>();
            for (CountryCC prefijo : resultPage) {
                mapPrefijos.put(prefijo.getPrefix(), prefijo.getCountry().getName());
            }

            // Buscamos el prefijo de MSISDN
            String msisdn = sondaResult.getMsisdn();
            String prefijoMsisdn = null;
            for (int i = 1; i < 7; i++) {
                if (mapPrefijos.containsKey(msisdn.substring(0, i))) {
                    prefijoMsisdn = msisdn.substring(0, i);
                    if (i == 1 && "1".equals(prefijoMsisdn)) { // Si es de Norte America comprobamos si esta asociado a algún area concreta, sino se deja Canada, EEUU por defecto
                        String prefijoEEUU = msisdn.substring(0, 3);
                        if (mapPrefijos.containsKey(prefijoEEUU)) {
                            prefijoMsisdn = prefijoEEUU;
                        }
                    }
                    sondaResult.setMsisdnPrefijo(prefijoMsisdn);
                    String pais = mapPrefijos.get(prefijoMsisdn);
                    sondaResult.setMsisdnPais(pais);
                    break;
                }
            }

            // Buscamos el prefijo de MSC
            String msc = sondaResult.getMscgt();
            String prefijoMsc = null;
            // Comprobamos cual es su prefijo
            for (int i = 1; i < 7; i++) {
                if (mapPrefijos.containsKey(msc.substring(0, i))) {
                    prefijoMsc = msc.substring(0, i);
                    if (i == 1 && "1".equals(prefijoMsisdn)) { // Si es de Norte America comprobamos si esta asociado a algún area concreta, sino se deja Canada, EEUU por defecto
                        String prefijoEEUU = msisdn.substring(0, 3);
                        if (mapPrefijos.containsKey(prefijoEEUU)) {
                            prefijoMsisdn = prefijoEEUU;
                        }
                    }
                    sondaResult.setMscPrefijo(prefijoMsc);
                    String pais = mapPrefijos.get(prefijoMsc);
                    sondaResult.setMsccountry(pais);
                    break;
                }
            }

            if (prefijoMsc != null && prefijoMsisdn != null) {
                if (prefijoMsc.equals(prefijoMsisdn))
                    sondaResult.setRoaming("No");
                else
                    sondaResult.setRoaming("Si");
            }
        } catch (Exception e) {
            logErrAndException(PROCESSING_PREFIXES_ERROR, PROCESSING_PREFIXES_ERROR, e, true);
        }
    }

    private Operator getOperator(MccMnc mccMnc) {

        List<Operator> operators = operatorService.getOperatorByMcc_Mnc(mccMnc.getMcc(), mccMnc.getMnc());

        return operators.get(0);

    }

    public List<String> getTrustOperatorsList() {
        return RequestFileWriterService.getStringList(appConfigService, log);
    }
}
