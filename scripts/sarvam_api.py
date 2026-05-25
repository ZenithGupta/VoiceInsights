import os
import requests
import mimetypes

class SarvamAPI:
    """
    Wrapper for Sarvam AI APIs.
    Requires SARVAM_API_KEY environment variable to be set.
    """
    BASE_URL = "https://api.sarvam.ai"

    def __init__(self, api_key=None):
        self.api_key = api_key or os.getenv("SARVAM_API_KEY")
        if not self.api_key:
            raise ValueError("API Key for Sarvam AI must be provided or set in SARVAM_API_KEY env var.")
        
        self.headers = {
            "api-subscription-key": self.api_key
        }

    def speech_to_text(self, file_path, language_code="hi-IN"):
        """
        Transcribes the given audio file using Sarvam's Speech-to-Text API.
        
        Args:
            file_path: Path to the audio file (e.g., .wav, .m4a)
            language_code: Language code for transcription (e.g., hi-IN, en-IN)
            
        Returns:
            JSON response from the API containing the transcript.
        """
        url = f"{self.BASE_URL}/speech-to-text-translate"
        
        mime_type, _ = mimetypes.guess_type(file_path)
        if not mime_type:
            mime_type = 'audio/wav'

        with open(file_path, "rb") as f:
            files = {
                'file': (os.path.basename(file_path), f, mime_type)
            }
            data = {
                'prompt': '',
                'model': 'saaras:v1' # using saaras model
            }
            
            response = requests.post(
                url, 
                headers=self.headers, 
                files=files,
                data=data
            )

        if response.status_code != 200:
            print(f"Error {response.status_code}: {response.text}")
            response.raise_for_status()

        return response.json()

if __name__ == "__main__":
    # Example usage:
    # api = SarvamAPI(api_key="your_sarvam_pro_key")
    # transcript = api.speech_to_text("clean_audio.wav")
    # print(transcript)
    pass
