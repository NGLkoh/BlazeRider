/**
 * Import function triggers from their respective submodules:
 *
 * const {onCall} = require("firebase-functions/v2/https");
 * const {onDocumentWritten} = require("firebase-functions/v2/firestore");
 *
 * See a full list of supported triggers at https://firebase.google.com/docs/functions
 */

const {setGlobalOptions} = require("firebase-functions");
const {onCall} = require("firebase-functions/v2/https");
const {onDocumentCreated} = require("firebase-functions/v2/firestore");
const logger = require("firebase-functions/logger");
const admin = require('firebase-admin');

admin.initializeApp();

setGlobalOptions({ maxInstances: 10 });

exports.sendChatNotification = onCall(async (request) => {
  const { chatId, senderName, message, chatName, recipientTokens } = request.data;
  logger.info('Received data:', request.data);

  if (!chatId || !senderName || !message || !chatName || !recipientTokens || !Array.isArray(recipientTokens) || recipientTokens.length === 0) {
    throw new functions.https.HttpsError('invalid-argument', 'Invalid or missing arguments.');
  }

  const payload = {
    notification: {
      title: chatName,
      body: `${senderName}: ${message}`,
    },
    data: {
      chatId: chatId,
    },
  };

  try {
    const response = await admin.messaging().sendEachForMulticast({
      tokens: recipientTokens,
      notification: payload.notification,
      data: payload.data,
    });
    logger.info(`Successfully sent notifications to ${response.successCount} devices`);
    if (response.failureCount > 0) {
        logger.warn(`Errors sending to ${response.failureCount} devices.`);
    }
    return { success: true };
  } catch (error) {
    logger.error('Error sending notifications:', error);
    throw new functions.https.HttpsError('internal', 'Failed to send notifications.');
  }
});

exports.sendReactionNotification = onDocumentCreated("posts/{postId}/reactions/{reactionId}", async (event) => {
    const snapshot = event.data;
    if (!snapshot) {
        logger.log("No data associated with the event");
        return;
    }
    const reaction = snapshot.data();
    const postId = event.params.postId;
    const reactorId = reaction.userId;

    const postRef = admin.firestore().collection('posts').doc(postId);
    const postDoc = await postRef.get();
    if (!postDoc.exists) {
        logger.log(`Post ${postId} not found.`);
        return;
    }

    const postAuthorId = postDoc.data().userId;

    if (postAuthorId === reactorId) {
        logger.log("Reaction is by post author. No notification sent.");
        return;
    }

    const reactorRef = admin.firestore().collection('users').doc(reactorId);
    const reactorDoc = await reactorRef.get();
    if (!reactorDoc.exists) {
         logger.log(`Reactor user ${reactorId} not found.`);
         return;
    }
    const reactorName = `${reactorDoc.data().firstName} ${reactorDoc.data().lastName}`.trim();

    const authorRef = admin.firestore().collection('users').doc(postAuthorId);
    const authorDoc = await authorRef.get();
    if (!authorDoc.exists) {
         logger.log(`Author user ${postAuthorId} not found.`);
         return;
    }
    const fcmToken = authorDoc.data().fcmToken;

    if (!fcmToken) {
        logger.log(`FCM token for user ${postAuthorId} not found.`);
        return;
    }

    const payload = {
        notification: {
            title: 'New Reaction',
            body: `${reactorName} reacted to your post.`,
        },
        data: {
            postId: postId,
            type: 'REACTION'
        },
    };

    try {
        await admin.messaging().sendToDevice(fcmToken, payload);
        logger.log(`Successfully sent reaction notification to ${postAuthorId}`);
    } catch (error) {
        logger.error(`Error sending reaction notification to ${postAuthorId}:`, error);
    }
});

exports.sendCommentNotification = onDocumentCreated("posts/{postId}/comments/{commentId}", async (event) => {
    const snapshot = event.data;
    if (!snapshot) {
        logger.log("No data associated with the event");
        return;
    }
    const comment = snapshot.data();
    const postId = event.params.postId;
    const commenterId = comment.userId;

    const postRef = admin.firestore().collection('posts').doc(postId);
    const postDoc = await postRef.get();
    if (!postDoc.exists) {
         logger.log(`Post ${postId} not found.`);
        return;
    }

    const postAuthorId = postDoc.data().userId;

    if (postAuthorId === commenterId) {
        logger.log("Comment is by post author. No notification sent.");
        return;
    }

    const commenterRef = admin.firestore().collection('users').doc(commenterId);
    const commenterDoc = await commenterRef.get();
    if (!commenterDoc.exists) {
         logger.log(`Commenter user ${commenterId} not found.`);
         return;
    }
    const commenterName = `${commenterDoc.data().firstName} ${commenterDoc.data().lastName}`.trim();
    
    const authorRef = admin.firestore().collection('users').doc(postAuthorId);
    const authorDoc = await authorRef.get();
    if (!authorDoc.exists) {
         logger.log(`Author user ${postAuthorId} not found.`);
         return;
    }
    const fcmToken = authorDoc.data().fcmToken;

    if (!fcmToken) {
        logger.log(`FCM token for user ${postAuthorId} not found.`);
        return;
    }
    
    const payload = {
        notification: {
            title: 'New Comment',
            body: `${commenterName} commented: "${comment.content}"`,
        },
        data: {
            postId: postId,
            type: 'COMMENT'
        },
    };
    try {
        await admin.messaging().sendToDevice(fcmToken, payload);
        logger.log(`Successfully sent comment notification to ${postAuthorId}`);
    } catch (error) {
        logger.error(`Error sending comment notification to ${postAuthorId}:`, error);
    }
});
