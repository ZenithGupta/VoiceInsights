import onnxruntime as ort

model_path = r"app\src\main\assets\silero_vad_v4.onnx"
session = ort.InferenceSession(model_path)

print("=== INPUTS ===")
for inp in session.get_inputs():
    print(f"  Name: {inp.name:10s}  Shape: {str(inp.shape):20s}  Type: {inp.type}")

print("\n=== OUTPUTS ===")
for out in session.get_outputs():
    print(f"  Name: {out.name:10s}  Shape: {str(out.shape):20s}  Type: {out.type}")
