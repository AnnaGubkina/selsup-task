package org.example;

import lombok.*;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import com.google.gson.Gson;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The CrptApi class provides a client interface to interact with the API of Честный знак (Honest Sign) system.
 * It allows users to create documents with specified data and signature and send them to the API.
 * The API requests are rate-limited based on the specified request limit per time unit.
 */

public class CrptApi {
    private final Lock lock = new ReentrantLock();
    private final TimeUnit timeUnit;
    private final int requestLimit;
    private int requestCount;
    private long lastResetTime;
    private final String CLIENT_TOKEN = "Bearer_token"; // заменим на реальный токен
    private final String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create"; // заменим на реальный URL для создания документа API Честного знака

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be greater than zero");
        }
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.requestCount = 0;
        this.lastResetTime = System.currentTimeMillis();
    }


    /**
     * Creates a new document and sends it to the Честный знак API.
     *
     * @param document  The document data to be sent.
     * @param signature The signature of the document.
     * @throws IllegalArgumentException If the document format or type is not supported or missing.
     * @throws ApiRequestException      If the API request fails with a non-200 status code.
     */
    public void createDocument(Document document, String signature) {
        DocumentRequest documentRequest = new DocumentRequest(document, signature);
        HttpEntity httpEntity = documentRequest.toHttpEntity();

        lock.lock();
        try {
            // Проверка, нужно ли обнулить счетчик запросов
            long currentTime = System.currentTimeMillis();
            long timeElapsed = currentTime - lastResetTime;
            if (timeElapsed >= timeUnit.toMillis(1)) {
                requestCount = 0;
                lastResetTime = currentTime;
            }

            // Проверяем, не превышен ли лимит запросов
            if (requestCount >= requestLimit) {
                try {
                    // Если лимит превышен, ждем до окончания текущего интервала
                    long sleepTime = timeUnit.toMillis(1) - timeElapsed;
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // После ожидания обнуляем счетчик и время
                requestCount = 0;
                lastResetTime = System.currentTimeMillis();
            }
            // Выполняем запрос к API Честный знак и обрабатываем результаты
            apiRequest(httpEntity);

            requestCount++;
        } finally {
            lock.unlock();
        }
    }


    private void apiRequest(HttpEntity httpEntity) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(URL);
            httpPost.setEntity(httpEntity);

            // Добавляем заголовки
            // Проверку токена я не делаю, так как предполагаю, что токен проверяется средствами security и сюда попадает уже валидный
            httpPost.setHeader("content-type", "application/json");
            httpPost.setHeader("Authorization", CLIENT_TOKEN);

            // Получаем и обрабатываем ответ
            apiResponseProcessing(httpClient, httpPost);

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private void apiResponseProcessing(CloseableHttpClient httpClient, HttpPost httpPost) throws IOException {
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            int statusCode = response.getStatusLine().getStatusCode();
            // В случае неудачного запроса выбрасываем наше собственное исключение
            if (statusCode != 200) {
                throw new ApiRequestException(statusCode, "Failed to send API request");
            }
            HttpEntity responseEntity = response.getEntity();

            if (responseEntity != null) {
                // обработка JSON-ответа с результатами создания документа
                String responseJson = EntityUtils.toString(responseEntity);
                DocumentResponse documentResponse = new Gson().fromJson(responseJson, DocumentResponse.class);
                String documentId = documentResponse.getValue();
                if (documentId != null) {
                    // Обработка успешного ответа
                    System.out.println("The document was created successfully. Document ID: " + documentId);
                } else {
                    // Обработка неуспешного ответа (ошибка)
                    System.out.println("Error creating document.");
                    if (documentResponse.getErrorCode() != null) {
                        System.out.println("Error code: " + documentResponse.getErrorCode());
                    }
                    if (documentResponse.getErrorMessage() != null) {
                        System.out.println("Error message: " + documentResponse.getErrorMessage());
                    }
                    if (documentResponse.getErrorDescription() != null) {
                        System.out.println("Description: " + documentResponse.getErrorDescription());
                    }
                }
            }
        } catch (ApiRequestException e) {
            e.printStackTrace();
        }
    }


    /**
     * The document model class
     */
    @Getter
    public static class Document {
        private String productDocument;
        private String productGroup;
        private String documentFormat;
        private String type;

        public Document(String productDocument, String productGroup, String documentFormat, String type) {
            this.productDocument = productDocument;
            this.productGroup = productGroup;
            this.documentFormat = documentFormat;
            this.type = type;
        }
    }


    /**
     * Class to represent request data
     */
    private static class DocumentRequest {
        private String productDocument;
        private String productGroup;
        private DocumentFormat documentFormat;
        private Type type;
        private String signature;

        public DocumentRequest(Document document, String signature) {
            this.productDocument = document.getProductDocument();
            this.productGroup = document.getProductGroup();
            this.signature = signature;
            // Определение documentFormat на основе переданного формата документа
            if (document.getDocumentFormat() != null) {
                String format = document.getDocumentFormat().toString();
                switch (format) {
                    case ("json"):
                        this.documentFormat = DocumentFormat.MANUAL;
                        break;
                    case ("csv"):
                        this.documentFormat = DocumentFormat.CSV;
                        break;
                    case ("xml"):
                        this.documentFormat = DocumentFormat.XML;
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported document format: " + format);
                }
            } else {
                throw new IllegalArgumentException("Document format is required");
            }

            // Определение type на основе переданного типа документа
            if (document.getType() != null) {
                this.type = Type.LP_INTRODUCE_GOODS;
            } else {
                throw new IllegalArgumentException("Document type is required");

            }
        }

        // Метод для преобразования объекта DocumentRequest в HttpEntity
        public HttpEntity toHttpEntity() {
            String requestJson = new Gson().toJson(this);
            return new StringEntity(requestJson, ContentType.APPLICATION_JSON);
        }
    }


    /**
     * Class for representing the response from the API
     */
    @Getter
    @AllArgsConstructor
    private static class DocumentResponse {
        private String value;
        private String errorCode;
        private String errorMessage;
        private String errorDescription;
    }


    enum DocumentFormat {
        MANUAL,
        CSV,
        XML
    }

    enum Type {
        LP_INTRODUCE_GOODS
    }


    static class ApiRequestException extends Exception {
        private final int statusCode;
        private final String errorMessage;

        public ApiRequestException(int statusCode, String errorMessage) {
            super("API request failed with status code: " + statusCode + ", Error message: " + errorMessage);
            this.statusCode = statusCode;
            this.errorMessage = errorMessage;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}


