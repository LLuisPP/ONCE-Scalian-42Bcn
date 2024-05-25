import tensorflow as tf

# Carga el modelo .h5
model = tf.keras.models.load_model('./model.h5')

# Convierte el modelo a formato TFLite
converter = tf.lite.TFLiteConverter.from_keras_model(model)
tflite_model = converter.convert()

# Guarda el modelo en un archivo .tflite
with open('model.tflite', 'wb') as f:
    f.write(tflite_model)