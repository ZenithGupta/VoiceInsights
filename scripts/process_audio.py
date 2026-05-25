import os
import io
import datetime
import torch
import torchaudio
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload, MediaFileUpload
from sarvam_api import SarvamAPI

# If modifying these scopes, delete the file token.json.
SCOPES = ['https://www.googleapis.com/auth/drive']

def get_drive_service():
    creds = None
    if os.path.exists('token.json'):
        creds = Credentials.from_authorized_user_file('token.json', SCOPES)
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            flow = InstalledAppFlow.from_client_secrets_file(
                'credentials.json', SCOPES)
            creds = flow.run_local_server(port=0)
        with open('token.json', 'w') as token:
            token.write(creds.to_json())
    return build('drive', 'v3', credentials=creds)

def download_file(service, file_id, file_name, dest_folder="downloads"):
    os.makedirs(dest_folder, exist_ok=True)
    request = service.files().get_media(fileId=file_id)
    file_path = os.path.join(dest_folder, file_name)
    fh = io.FileIO(file_path, 'wb')
    downloader = MediaIoBaseDownload(fh, request)
    done = False
    print(f"Downloading {file_name}...")
    while done is False:
        status, done = downloader.next_chunk()
    return file_path

def upload_file(service, file_path, file_name, parent_id=None):
    print(f"Uploading {file_name} to Drive...")
    file_metadata = {'name': file_name}
    if parent_id:
        file_metadata['parents'] = [parent_id]
    
    media = MediaFileUpload(file_path, mimetype='audio/mp4', resumable=True)
    file = service.files().create(body=file_metadata, media_body=media, fields='id').execute()
    print(f"Uploaded successfully with ID: {file.get('id')}")
    return file.get('id')

def convert_to_m4a(input_wav, output_m4a):
    import subprocess
    print(f"Converting {input_wav} to {output_m4a}...")
    try:
        subprocess.run(['ffmpeg', '-y', '-i', input_wav, '-c:a', 'aac', output_m4a], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except FileNotFoundError:
        print("Error: ffmpeg is not installed or not in PATH.")
        return False
    except subprocess.CalledProcessError as e:
        print(f"Error during ffmpeg conversion: {e}")
        return False

def process_audio_with_vad(file_path, output_path):
    print(f"Applying Silero VAD to {file_path}...")
    # Load Silero VAD
    model, utils = torch.hub.load(repo_or_dir='snakers4/silero-vad',
                                  model='silero_vad',
                                  force_reload=False)
    
    (get_speech_timestamps, save_audio, read_audio, VADIterator, collect_chunks) = utils

    try:
        # Read audio (Silero VAD expects 16kHz)
        wav = read_audio(file_path, sampling_rate=16000)
        
        # Get speech timestamps
        speech_timestamps = get_speech_timestamps(wav, model, sampling_rate=16000)
        
        if not speech_timestamps:
            print("No speech detected.")
            return None
        
        # Collect speech chunks
        cleaned_audio = collect_chunks(speech_timestamps, wav)
        
        # Save output
        save_audio(output_path, cleaned_audio, sampling_rate=16000)
        print(f"Cleaned audio saved to {output_path}")
        return output_path
    except Exception as e:
        print(f"Error processing {file_path} with VAD: {e}")
        return None

def main():
    if not os.path.exists('credentials.json'):
        print("Please download credentials.json from Google Cloud Console and place it in this directory.")
        return

    service = get_drive_service()
    
    # Calculate date 45 days ago
    date_threshold = (datetime.datetime.utcnow() - datetime.timedelta(days=45)).isoformat() + "Z"
    
    # Note: .m4a might be stored as audio/mp4 or audio/m4a in Drive
    query = f"(mimeType='audio/mp4' or mimeType='audio/m4a' or name contains '.m4a') and modifiedTime > '{date_threshold}' and trashed=false"
    print(f"Searching Drive for files modified after {date_threshold}...")
    
    results = service.files().list(
        q=query, pageSize=100, fields="nextPageToken, files(id, name, modifiedTime, parents)").execute()
    items = results.get('files', [])

    if not items:
        print('No audio files found.')
        return

    print(f"Found {len(items)} files.")
    
    sarvam_api = None
    if os.getenv("SARVAM_API_KEY"):
        sarvam_api = SarvamAPI()
        print("Sarvam API is configured.")
    else:
        print("SARVAM_API_KEY not set. Transcription will be skipped.")
    
    for item in items:
        file_name = item['name']
        file_id = item['id']
        parents = item.get('parents', [])
        parent_id = parents[0] if parents else None
        
        # Download
        local_path = download_file(service, file_id, file_name)
        
        # Process VAD
        output_wav = os.path.join("cleaned", f"clean_{file_name}.wav")
        os.makedirs("cleaned", exist_ok=True)
        
        clean_path_wav = process_audio_with_vad(local_path, output_wav)
        
        clean_path_m4a = None
        if clean_path_wav:
            # Convert to m4a
            m4a_filename = f"clean_{file_name}" if file_name.endswith('.m4a') else f"clean_{file_name}.m4a"
            output_m4a = os.path.join("cleaned", m4a_filename)
            if convert_to_m4a(clean_path_wav, output_m4a):
                clean_path_m4a = output_m4a
                # Upload back to Drive
                upload_file(service, clean_path_m4a, m4a_filename, parent_id)
        
        # Optional: Run Sarvam Speech-to-text
        if clean_path_m4a and sarvam_api:
            print(f"Running Speech-to-Text on {clean_path_m4a}...")
            try:
                result = sarvam_api.speech_to_text(clean_path_m4a)
                print(f"Transcript for {file_name}:")
                print(result)
            except Exception as e:
                print(f"Failed to transcribe {file_name}: {e}")

if __name__ == '__main__':
    main()
