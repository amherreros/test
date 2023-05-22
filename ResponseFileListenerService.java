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

}
