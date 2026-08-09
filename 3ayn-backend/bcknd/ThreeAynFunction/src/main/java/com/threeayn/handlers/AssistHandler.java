package com.threeayn.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.threeayn.util.ApiResponse;
import com.threeayn.util.RequestParser;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * POST /assist { text: spoken request, image?: base64 frame, lang }
 * -> { text: spoken reply, action?: {type, arg} }
 *
 * The conversational brain. One Bedrock Nova 2 Lite call handles EVERYTHING:
 * general questions (weather-caveated, cooking, facts), scene description,
 * reading text in the frame, identifying, and emitting app ACTIONS.
 *
 * The model answers in natural Arabic AND, when the request needs the app to
 * DO something, appends a machine tag on its own line:
 *   [[ACTION:read]]              read the text in the frame aloud
 *   [[ACTION:identify]]          who is in front of me
 *   [[ACTION:enroll:NAME]]       save this face as NAME
 *   [[ACTION:monitor:NAME]]      let NAME monitor me (trusted viewer)
 *   [[ACTION:find:OBJECT]]       locate an object
 *   // ADD NEW TOOLS HERE  (e.g. [[ACTION:navigate:DEST]] when Location Service lands)
 * The frontend strips the tag, speaks the prose, and executes the action.
 */
public class AssistHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final BedrockRuntimeClient BEDROCK = BedrockRuntimeClient.create();
    private static final String MODEL_ID = System.getenv().getOrDefault("MODEL_ID", "global.amazon.nova-2-lite-v1:0");

    private static final String SYSTEM_AR =
        "أنت \"عين\"، مساعد ذكي لشخص كفيف أو ضعيف البصر يتحدث العربية. "
      + "الكاميرا هي عيناه. أجب بإيجاز ووضوح، بجملتين أو ثلاث كحد أقصى، بلغة محكية بسيطة. "
      + "يمكنك: الإجابة عن أي سؤال عام (طبخ، معلومات، وقت)، ووصف ما تراه الكاميرا، وقراءة النص، والتعرف على الأشخاص. "
      + "إذا لم تكن متأكداً، قل ذلك بصدق ولا تخترع. "
      + "إذا طلب المستخدم إجراءً يحتاج التطبيق لتنفيذه، أضف سطراً منفصلاً في النهاية بأحد الأشكال التالية فقط: "
      + "[[ACTION:read]] لقراءة النص أمامه، "
      + "[[ACTION:identify]] لمعرفة من أمامه، "
      + "[[ACTION:enroll:الاسم]] لحفظ وجه شخص باسمه، "
      + "[[ACTION:monitor:الاسم]] للسماح لشخص بمراقبته، "
      + "[[ACTION:find:الشيء]] لتحديد مكان غرض. "
      + "لا تذكر هذه الأوامر للمستخدم؛ فقط تكلم بشكل طبيعي ثم أضف السطر إن لزم. "
      + "لأسئلة الطقس أو الاتجاهات المباشرة قل إنك لا تستطيع التحقق منها بعد.";

    private static final String SYSTEM_EN =
        "You are \"3ayn\", an intelligent assistant for a blind or low-vision person. "
      + "The camera is their eyes. Answer briefly and clearly, two or three sentences max, in plain speech. "
      + "You can answer any general question, describe what the camera sees, read text, and identify people. "
      + "If unsure, say so honestly; never invent. "
      + "If the user asks for something the app must DO, add a separate final line in one of these forms only: "
      + "[[ACTION:read]], [[ACTION:identify]], [[ACTION:enroll:NAME]], [[ACTION:monitor:NAME]], [[ACTION:find:OBJECT]]. "
      + "Never mention these tags to the user. For live weather or directions, say you can't check those yet.";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            RequestParser req = new RequestParser(event);
            String lang = req.lang();
            String userText = req.requiredField("text");
            byte[] image = null;
            try { image = req.imageBytes(); } catch (Exception ignore) { /* image optional */ }

            String system = lang.equals("ar") ? SYSTEM_AR : SYSTEM_EN;

            List<ContentBlock> content = new ArrayList<>();
            if (image != null) {
                content.add(ContentBlock.fromImage(ImageBlock.builder()
                    .format(ImageFormat.JPEG)
                    .source(ImageSource.fromBytes(SdkBytes.fromByteArray(image)))
                    .build()));
            }
            content.add(ContentBlock.fromText(userText));
            Message msg = Message.builder().role(ConversationRole.USER).content(content).build();

            ConverseResponse resp = BEDROCK.converse(r -> r
                .modelId(MODEL_ID)
                .system(SystemContentBlock.fromText(system))
                .messages(msg)
                .inferenceConfig(c -> c.maxTokens(400).temperature(0.4f)));

            String raw = resp.output().message().content().get(0).text().trim();

            // split spoken prose from the optional action tag
            String spoken = raw;
            String actionType = null, actionArg = null;
            int tagStart = raw.indexOf("[[ACTION:");
            if (tagStart >= 0) {
                int tagEnd = raw.indexOf("]]", tagStart);
                if (tagEnd > tagStart) {
                    String tag = raw.substring(tagStart + 9, tagEnd); // after "[[ACTION:"
                    spoken = raw.substring(0, tagStart).trim();
                    String[] parts = tag.split(":", 2);
                    actionType = parts[0].trim();
                    if (parts.length > 1) actionArg = parts[1].trim();
                }
            }
            if (spoken.isBlank()) spoken = lang.equals("ar") ? "حسناً" : "Okay";

            Map<String, Object> out = new java.util.HashMap<>();
            out.put("text", spoken);
            if (actionType != null) {
                Map<String, String> action = new java.util.HashMap<>();
                action.put("type", actionType);
                if (actionArg != null) action.put("arg", actionArg);
                out.put("action", action);
            }
            return ApiResponse.success(out);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("AssistHandler error: " + e);
            return ApiResponse.error(500, "Assistant failed");
        }
    }
}
